package com.kece.database.service;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.database.dto.DbQuestionRequest;
import com.kece.database.dto.DbQuestionVO;
import com.kece.database.dto.DbTestCaseRequest;
import com.kece.database.dto.DbTestCaseVO;
import com.kece.database.entity.DbQuestion;
import com.kece.database.entity.DbTestCase;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.database.mapper.DbTestCaseMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;


/** 数据库题目业务逻辑。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbQuestionService {

    private static final String CACHE_LIST_PREFIX = "db:question:cache:list:";
    private static final String CACHE_LIST_KEYS_SET = "db:question:cache:list_keys";
    private static final String CACHE_DETAIL_PREFIX = "db:question:cache:detail:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final DbQuestionMapper questionMapper;
    private final DbTestCaseMapper testCaseMapper;
    private final DbSubmissionMapper submissionMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PageResult<DbQuestionVO> list(
            String type, String keyword, int page, int size, boolean all) {
        return list(type, keyword, null, page, size, all);
    }

    public PageResult<DbQuestionVO> list(
            String type, String keyword, String userStatus, int page, int size, boolean all) {
        PageParam p = PageParam.of(page, size);
        Integer status = (all && isPrivileged()) ? null : 1;

        boolean isPublicQuery = (userStatus == null || userStatus.isBlank()) && (!all || !isPrivileged());
        String cacheKey = null;
        if (isPublicQuery) {
            cacheKey = buildListCacheKey(p.page(), p.size(), type, keyword);
            PageResult<DbQuestionVO> cached = getCachedList(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<Long> includeIds = null;
        List<Long> excludeIds = null;
        if (userStatus != null && !userStatus.isBlank()) {
            Long userId = AuthContextHolder.currentUserId();
            if (userId == null) {
                if ("SOLVED".equalsIgnoreCase(userStatus)) {
                    return new PageResult<>(List.of(), 0, p.page(), p.size());
                }
            } else {
                List<Long> solvedIds = submissionMapper.findAcceptedQuestionIds(userId);
                if ("SOLVED".equalsIgnoreCase(userStatus)) {
                    if (solvedIds == null || solvedIds.isEmpty()) {
                        return new PageResult<>(List.of(), 0, p.page(), p.size());
                    }
                    includeIds = solvedIds;
                } else if ("UNSOLVED".equalsIgnoreCase(userStatus)) {
                    if (solvedIds != null && !solvedIds.isEmpty()) {
                        excludeIds = solvedIds;
                    }
                }
            }
        }

        List<DbQuestion> items =
                questionMapper.findPage(
                        type, keyword, status, null, includeIds, excludeIds, p.offset(), p.size());
        long total = questionMapper.count(type, keyword, status, null, includeIds, excludeIds);
        PageResult<DbQuestionVO> result = new PageResult<>(
                items.stream().map(this::toListVO).toList(),
                total,
                p.page(),
                p.size());
        if (isPublicQuery && cacheKey != null) {
            putCachedList(cacheKey, result);
        }
        return result;
    }

    /** 后台数据库题列表：ADMIN 可按 createdBy 筛选，非 ADMIN 仅见自己出的题。 */
    public PageResult<DbQuestionVO> listAdmin(
            String type, String keyword, Integer status, Long createdBy, int page, int size) {
        PageParam p = PageParam.of(page, size);
        Long targetCreatedBy = createdBy;
        AuthUser user = AuthContextHolder.get();
        if (user == null || !user.hasRole(Role.ADMIN)) {
            targetCreatedBy = AuthContextHolder.currentUserId();
        }
        List<DbQuestion> items =
                questionMapper.findPage(
                        type, keyword, status, targetCreatedBy, null, null, p.offset(), p.size());
        long total = questionMapper.count(type, keyword, status, targetCreatedBy, null, null);
        return new PageResult<>(
                items.stream().map(this::toListVO).toList(), total, p.page(), p.size());
    }

    public DbQuestionVO detail(Long id) {
        boolean privileged = isPrivileged();
        String detailKey = CACHE_DETAIL_PREFIX + id + (privileged ? ":priv" : ":pub");
        try {
            String cached = redis.opsForValue().get(detailKey);
            if (cached != null && !cached.isBlank()) {
                DbQuestionVO vo = objectMapper.readValue(cached, DbQuestionVO.class);
                if (vo != null && (vo.getStatus() == null || vo.getStatus() == 1 || privileged)) {
                    return vo;
                }
            }
        } catch (Exception e) {
            log.warn("读取数据库题目详情缓存失败，降级查库 id={}: {}", id, e.getMessage());
        }

        DbQuestion question = getQuestionOrThrow(id);
        if (question.getStatus() == null || question.getStatus() != 1) {
            if (!canManage(question)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
            }
        }
        DbQuestionVO vo = toVO(question, privileged, true);
        if (question.getStatus() != null && question.getStatus() == 1) {
            try {
                redis.opsForValue().set(detailKey, objectMapper.writeValueAsString(vo), CACHE_TTL);
            } catch (Exception e) {
                log.warn("写入数据库题目详情缓存失败 id={}: {}", id, e.getMessage());
            }
        }
        return vo;
    }

    @Transactional
    public DbQuestionVO create(DbQuestionRequest request) {
        DbQuestion question = new DbQuestion();
        applyRequest(question, request);
        question.setCreatedBy(AuthContextHolder.currentUserId());
        questionMapper.insert(question);
        replaceTestCases(question.getId(), request.getTestCases());
        evictDbQuestionCache(question.getId());
        return toVO(question, true, true);
    }

    @Transactional
    public DbQuestionVO update(Long id, DbQuestionRequest request) {
        DbQuestion question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        applyRequest(question, request);
        questionMapper.update(question);
        replaceTestCases(question.getId(), request.getTestCases());
        evictDbQuestionCache(id);
        return toVO(question, true, true);
    }

    /** 删除数据库题：无提交记录时出题人/管理员可删；有提交记录时仅 ADMIN 可强制删除（级联清理提交记录）。 */
    @Transactional
    public void delete(Long id) {
        DbQuestion question = getQuestionOrThrow(id);
        checkOwnerOrAdmin(question);
        AuthUser user = AuthContextHolder.get();
        boolean admin = user != null && user.hasRole(Role.ADMIN);
        if (submissionMapper.existsByQuestionId(id) && !admin) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题目已有提交记录，仅管理员可强制删除");
        }
        if (admin) {
            submissionMapper.deleteByQuestionId(id);
        }
        testCaseMapper.deleteByQuestionId(id);
        questionMapper.deleteById(id);
        evictDbQuestionCache(id);
    }

    private DbQuestion getQuestionOrThrow(Long id) {
        DbQuestion question = questionMapper.findById(id);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在");
        }
        return question;
    }

    private void checkOwnerOrAdmin(DbQuestion question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN) || question.getCreatedBy().equals(user.getId())) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private boolean canManage(DbQuestion question) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            return false;
        }
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        return question.getCreatedBy().equals(user.getId());
    }

    private boolean isPrivileged() {
        AuthUser user = AuthContextHolder.get();
        return user != null && (user.hasRole(Role.TEACHER) || user.hasRole(Role.ADMIN));
    }

    private void applyRequest(DbQuestion question, DbQuestionRequest request) {
        question.setTitle(request.getTitle());
        question.setDescription(request.getDescription());
        question.setDialect(
                request.getDialect() == null || request.getDialect().isBlank()
                        ? "MYSQL"
                        : request.getDialect().trim().toUpperCase());
        question.setType(request.getType().trim().toUpperCase());
        question.setSchemaSql(request.getSchemaSql());
        question.setReferenceSql(request.getReferenceSql());
        question.setVerifySql(request.getVerifySql());
        question.setOrderSensitive(request.isOrderSensitive() ? 1 : 0);
        question.setStatus(request.getStatus() == null ? 1 : request.getStatus());
    }

    private void replaceTestCases(Long questionId, List<DbTestCaseRequest> cases) {
        testCaseMapper.deleteByQuestionId(questionId);
        int order = 0;
        for (DbTestCaseRequest c : cases) {
            DbTestCase tc = new DbTestCase();
            tc.setQuestionId(questionId);
            tc.setCaseOrder(order++);
            tc.setSeedSql(c.getSeedSql());
            tc.setExpectedOutput(c.getExpectedOutput());
            tc.setIsSample(c.getIsSample() != null && c.getIsSample() == 1 ? 1 : 0);
            testCaseMapper.insert(tc);
        }
    }

    private DbQuestionVO toListVO(DbQuestion question) {
        return DbQuestionVO.builder()
                .id(question.getId())
                .title(question.getTitle())
                .dialect(question.getDialect())
                .type(question.getType())
                .orderSensitive(
                        question.getOrderSensitive() != null && question.getOrderSensitive() == 1)
                .status(question.getStatus())
                .createdBy(question.getCreatedBy())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private DbQuestionVO toVO(DbQuestion question, boolean privileged, boolean withCases) {
        List<DbTestCaseVO> caseVOs = withCases ? buildCaseVOs(question.getId(), privileged) : List.of();
        User author = question.getCreatedBy() != null ? userMapper.findById(question.getCreatedBy()) : null;
        return DbQuestionVO.builder()
                .id(question.getId())
                .title(question.getTitle())
                .description(question.getDescription())
                .dialect(question.getDialect())
                .type(question.getType())
                .schemaSql(question.getSchemaSql())
                .referenceSql(privileged ? question.getReferenceSql() : null)
                .verifySql(privileged ? question.getVerifySql() : null)
                .orderSensitive(
                        question.getOrderSensitive() != null && question.getOrderSensitive() == 1)
                .status(question.getStatus())
                .createdBy(question.getCreatedBy())
                .authorName(author != null ? author.getUsername() : null)
                .authorAvatarUrl(author != null ? author.getAvatarUrl() : null)
                .testCases(caseVOs)
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private List<DbTestCaseVO> buildCaseVOs(Long questionId, boolean privileged) {
        List<DbTestCaseVO> result = new ArrayList<>();
        for (DbTestCase tc : testCaseMapper.findByQuestionId(questionId)) {
            boolean canSeeOutput =
                    privileged || (tc.getIsSample() != null && tc.getIsSample() == 1);
            result.add(
                    DbTestCaseVO.builder()
                            .id(tc.getId())
                            .caseOrder(tc.getCaseOrder())
                            .seedSql(tc.getSeedSql())
                            .expectedOutput(canSeeOutput ? tc.getExpectedOutput() : null)
                            .isSample(tc.getIsSample() == null ? 0 : tc.getIsSample())
                            .build());
        }
        return result;
    }
    private String buildListCacheKey(int page, int size, String type, String keyword) {
        return CACHE_LIST_PREFIX
                + page + ":"
                + size + ":"
                + (type == null ? "" : type.trim()) + ":"
                + (keyword == null ? "" : keyword.trim());
    }

    private PageResult<DbQuestionVO> getCachedList(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<PageResult<DbQuestionVO>>() {});
            }
        } catch (Exception e) {
            log.warn("读取数据库题目列表缓存失败 key={}: {}", key, e.getMessage());
        }
        return null;
    }

    private void putCachedList(String key, PageResult<DbQuestionVO> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redis.opsForValue().set(key, json, CACHE_TTL);
            redis.opsForSet().add(CACHE_LIST_KEYS_SET, key);
        } catch (Exception e) {
            log.warn("写入数据库题目列表缓存失败 key={}: {}", key, e.getMessage());
        }
    }

    /** 清除所有公共数据库题目列表分页缓存及指定题目详情缓存。 */
    private void evictDbQuestionCache(Long questionId) {
        try {
            Set<String> listKeys = redis.opsForSet().members(CACHE_LIST_KEYS_SET);
            if (listKeys != null && !listKeys.isEmpty()) {
                redis.delete(listKeys);
            }
            redis.delete(CACHE_LIST_KEYS_SET);
            if (questionId != null) {
                redis.delete(CACHE_DETAIL_PREFIX + questionId + ":pub");
                redis.delete(CACHE_DETAIL_PREFIX + questionId + ":priv");
            }
            log.info("已刷新数据库题目缓存，questionId={}", questionId);
        } catch (Exception e) {
            log.warn("刷新数据库题目缓存异常（已降级忽略）questionId={}: {}", questionId, e.getMessage());
        }
    }
}