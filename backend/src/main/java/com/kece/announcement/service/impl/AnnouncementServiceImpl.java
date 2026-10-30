package com.kece.announcement.service.impl;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.announcement.dto.AnnouncementBoardItemVO;
import com.kece.announcement.dto.AnnouncementDetailVO;
import com.kece.announcement.dto.AnnouncementRequest;
import com.kece.announcement.entity.Announcement;
import com.kece.announcement.mapper.AnnouncementMapper;
import com.kece.announcement.service.AnnouncementService;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 公告业务服务实现类。
 * 整合 Redis 缓存：看板列表走 notice:board:page:{page}:{size}，600s TTL + Jitter 防雪崩；
 * 管理端写操作（发布、编辑、置顶切换、上下架、删除）事务提交后原子清空 notice:board:* 缓存；
 * 查详情时原子自增 view_count。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    public static final String BOARD_CACHE_KEY_PREFIX = "notice:board:";
    public static final long BASE_TTL_SECONDS = 30L;
    public static final long MAX_JITTER_SECONDS = 10L;

    private final AnnouncementMapper announcementMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResult<AnnouncementBoardItemVO> listBoard(int page, int size) {
        PageParam safe = PageParam.of(page, size);
        String cacheKey = buildBoardCacheKey(safe.page(), safe.size());

        // 1. 尝试读取 Redis 缓存
        PageResult<AnnouncementBoardItemVO> cached = getBoardFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 缓存未命中：查询数据库（利用 idx_board_sort 索引）
        List<AnnouncementBoardItemVO> list = announcementMapper.findBoardPage(safe.offset(), safe.size());
        long total = announcementMapper.countBoard();
        PageResult<AnnouncementBoardItemVO> result = new PageResult<>(
                list != null ? list : List.of(),
                total,
                safe.page(),
                safe.size()
        );

        // 3. 异步/安全回填缓存（600s + Jitter 防雪崩）
        putBoardToCache(cacheKey, result);

        return result;
    }

    @Override
    @Transactional
    public AnnouncementDetailVO getDetail(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "公告ID不能为空");
        }

        Announcement announcement = announcementMapper.findById(id);
        if (announcement == null || !"PUBLISHED".equalsIgnoreCase(announcement.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公告不存在或未发布");
        }

        // 原子自增浏览次数
        announcementMapper.incrementViewCount(id);
        int currentViews = announcement.getViewCount() != null ? announcement.getViewCount() + 1 : 1;
        announcement.setViewCount(currentViews);

        // 自增浏览量后清空前台看板列表缓存，确保看板列表实时刷新最新阅读量
        evictBoardCacheAfterCommit();

        return toDetailVO(announcement);
    }

    @Override
    @Transactional(readOnly = true)
    public AnnouncementDetailVO getAdminDetail(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "公告ID不能为空");
        }

        Announcement announcement = announcementMapper.findById(id);
        if (announcement == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公告不存在");
        }

        return toDetailVO(announcement);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AnnouncementDetailVO> listAdmin(String status, int page, int size) {
        PageParam safe = PageParam.of(page, size);
        String filterStatus = (status != null && !status.isBlank()) ? status.trim().toUpperCase() : null;

        List<Announcement> list = announcementMapper.findAdminPage(filterStatus, safe.offset(), safe.size());
        long total = announcementMapper.countAdmin(filterStatus);

        List<AnnouncementDetailVO> voList = (list != null)
                ? list.stream().map(this::toDetailVO).toList()
                : List.of();

        return new PageResult<>(voList, total, safe.page(), safe.size());
    }

    @Override
    @Transactional
    public AnnouncementDetailVO create(AnnouncementRequest request) {
        String title = request.getTitle().trim();
        String content = request.getContent();

        // 摘要处理：为空时截取正文前100字纯文本
        String summary = request.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = extractSummary(content);
        } else {
            summary = summary.trim();
        }

        // 发布主体处理：优先取登录用户名，无则默认 'admin'
        String authorName = request.getAuthorName();
        if (authorName == null || authorName.isBlank()) {
            AuthUser currentUser = AuthContextHolder.get();
            if (currentUser != null && currentUser.getUsername() != null && !currentUser.getUsername().isBlank()) {
                authorName = currentUser.getUsername();
            } else {
                authorName = "admin";
            }
        } else {
            authorName = authorName.trim();
        }

        Long createdBy = AuthContextHolder.currentUserId();
        if (createdBy == null) {
            createdBy = 1L;
        }

        Integer isPinned = (request.getIsPinned() != null && request.getIsPinned() == 1) ? 1 : 0;
        Integer priority = request.getPriority() != null ? request.getPriority() : 0;
        String status = (request.getStatus() != null && !request.getStatus().isBlank())
                ? request.getStatus().trim().toUpperCase()
                : "PUBLISHED";

        Announcement announcement = Announcement.builder()
                .title(title)
                .summary(summary)
                .content(content)
                .authorName(authorName)
                .isPinned(isPinned)
                .priority(priority)
                .status(status)
                .viewCount(0)
                .createdBy(createdBy)
                .build();

        announcementMapper.insert(announcement);

        // 事务提交后原子清空 notice:board:* 缓存
        evictBoardCacheAfterCommit();

        return toDetailVO(announcement);
    }

    @Override
    @Transactional
    public AnnouncementDetailVO update(Long id, AnnouncementRequest request) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "公告ID不能为空");
        }

        Announcement existing = announcementMapper.findById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公告不存在");
        }

        String title = request.getTitle().trim();
        String content = request.getContent();

        String summary = request.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = extractSummary(content);
        } else {
            summary = summary.trim();
        }

        String authorName = request.getAuthorName();
        if (authorName != null && !authorName.isBlank()) {
            existing.setAuthorName(authorName.trim());
        }

        existing.setTitle(title);
        existing.setSummary(summary);
        existing.setContent(content);

        if (request.getIsPinned() != null) {
            existing.setIsPinned(request.getIsPinned() == 1 ? 1 : 0);
        }
        if (request.getPriority() != null) {
            existing.setPriority(request.getPriority());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            existing.setStatus(request.getStatus().trim().toUpperCase());
        }

        announcementMapper.update(existing);

        // 事务提交后原子清空 notice:board:* 缓存
        evictBoardCacheAfterCommit();

        return toDetailVO(existing);
    }

    @Override
    @Transactional
    public AnnouncementDetailVO updatePinned(Long id, Integer isPinned) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "公告ID不能为空");
        }

        Announcement existing = announcementMapper.findById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公告不存在");
        }

        int targetPinned;
        if (isPinned != null) {
            targetPinned = (isPinned == 1) ? 1 : 0;
        } else {
            // 未指定时自动切换/反转置顶状态
            targetPinned = (existing.getIsPinned() != null && existing.getIsPinned() == 1) ? 0 : 1;
        }

        announcementMapper.updatePinned(id, targetPinned);
        existing.setIsPinned(targetPinned);

        // 事务提交后原子清空 notice:board:* 缓存
        evictBoardCacheAfterCommit();

        return toDetailVO(existing);
    }

    @Override
    @Transactional
    public AnnouncementDetailVO updateStatus(Long id, String status) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "公告ID不能为空");
        }
        if (status == null || status.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标状态不能为空");
        }

        Announcement existing = announcementMapper.findById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公告不存在");
        }

        String targetStatus = status.trim().toUpperCase();
        announcementMapper.updateStatus(id, targetStatus);
        existing.setStatus(targetStatus);

        // 事务提交后原子清空 notice:board:* 缓存
        evictBoardCacheAfterCommit();

        return toDetailVO(existing);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "公告ID不能为空");
        }

        Announcement existing = announcementMapper.findById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公告不存在");
        }

        announcementMapper.deleteById(id);

        // 事务提交后原子清空 notice:board:* 缓存
        evictBoardCacheAfterCommit();
    }

    // ==============================================================
    // 缓存辅助方法与摘要提取
    // ==============================================================

    public String buildBoardCacheKey(int page, int size) {
        return BOARD_CACHE_KEY_PREFIX + "page:" + page + ":" + size;
    }

    private PageResult<AnnouncementBoardItemVO> getBoardFromCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                JavaType type = objectMapper.getTypeFactory().constructParametricType(
                        PageResult.class, AnnouncementBoardItemVO.class);
                return objectMapper.readValue(json, type);
            }
        } catch (Exception e) {
            log.warn("读取公告看板缓存失败 key={}: {}", key, e.getMessage());
        }
        return null;
    }

    private void putBoardToCache(String key, PageResult<AnnouncementBoardItemVO> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_SECONDS + 1);
            Duration ttl = Duration.ofSeconds(BASE_TTL_SECONDS + jitter);
            redis.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("写入公告看板缓存失败 key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 原子清空 notice:board:* 命名空间下的所有缓存。
     */
    public void evictBoardCache() {
        try {
            Set<String> keys = redis.keys(BOARD_CACHE_KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("已清空公告看板缓存，共清理 {} 个键", keys.size());
            }
        } catch (Exception e) {
            log.warn("清空公告看板缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 在事务成功提交后触发缓存失效；无活跃事务时立即执行。
     */
    private void evictBoardCacheAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictBoardCache();
                }
            });
        } else {
            evictBoardCache();
        }
    }

    /**
     * 从 Markdown 正文中提取纯文本前 100 字符作为导读摘要。
     */
    public static String extractSummary(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        // 1. 去除代码块 ```...```
        String text = content.replaceAll("(?s)```.*?```", " ");
        // 2. 去除行内代码 `...`
        text = text.replaceAll("`.*?`", " ");
        // 3. 提取链接文本 [text](url) -> text
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1");
        // 4. 去除图片标记 ![alt](url) -> " "
        text = text.replaceAll("!\\[[^\\]]*\\]\\([^\\)]+\\)", " ");
        // 5. 去除 HTML 标签 <...> -> " "
        text = text.replaceAll("<[^>]*>", " ");
        // 6. 去除 Markdown 特殊字符：# * _ ~ > - + = |
        text = text.replaceAll("[#*_~>\\-+=|]", " ");
        // 7. 合并多余空白字符
        text = text.replaceAll("\\s+", " ").trim();

        if (text.length() <= 100) {
            return text;
        }
        return text.substring(0, 100);
    }

    private AnnouncementDetailVO toDetailVO(Announcement a) {
        return AnnouncementDetailVO.builder()
                .id(a.getId())
                .title(a.getTitle())
                .summary(a.getSummary())
                .content(a.getContent())
                .authorName(a.getAuthorName())
                .isPinned(a.getIsPinned())
                .priority(a.getPriority())
                .status(a.getStatus())
                .viewCount(a.getViewCount())
                .createdBy(a.getCreatedBy())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
