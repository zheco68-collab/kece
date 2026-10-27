package com.kece.problemset.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.objective.mapper.ObjectiveSubmissionMapper;
import com.kece.problemset.dto.*;
import com.kece.problemset.entity.*;
import com.kece.problemset.mapper.*;
import com.kece.question.strategy.QuestionMeta;
import com.kece.question.strategy.QuestionStrategyFactory;
import com.kece.question.strategy.QuestionTypeStrategy;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 题集核心业务服务： 1) 支撑多题型（ALGO / DB / OBJECTIVE）分章节混合编排与快照冗余； 2) 读路径三级容灾：Redis 旁路缓存（带随机 TTL 防雪崩） ->
 * MySQL 预聚合表 -> 实时归并三张提交表并自动读自愈（Read-Repair）； 3) 写路径高可靠：乐观锁并发控制、唯一键幂等防重、原题下架软隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemSetService {

    private static final String CACHE_SKELETON_PREFIX = "pset:skeleton:";
    private static final long CACHE_BASE_TTL_SECONDS = 1500; // 25 分钟基础 TTL + 0~300s 随机抖动

    private final ProblemSetMapper problemSetMapper;
    private final ProblemSetSectionMapper sectionMapper;
    private final ProblemSetItemMapper itemMapper;
    private final ProblemSetUserProgressMapper progressMapper;
    private final SubmissionMapper submissionMapper;
    private final DbSubmissionMapper dbSubmissionMapper;
    private final ObjectiveSubmissionMapper objectiveSubmissionMapper;
    private final UserMapper userMapper;
    private final QuestionStrategyFactory strategyFactory;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 前台分页查询公开题集列表（附带当前登录用户的收藏状态与攻克进度）。 */
    public PageResult<ProblemSetSummaryVO> listPublic(
            String category,
            String difficulty,
            String keyword,
            boolean favoritedOnly,
            int page,
            int size) {
        PageParam p = PageParam.of(page, size);
        Long userId = AuthContextHolder.currentUserId();

        List<Long> onlyIds = null;
        if (favoritedOnly) {
            if (userId == null) {
                return new PageResult<>(List.of(), 0, p.page(), p.size());
            }
            onlyIds = progressMapper.findFavoritedSetIds(userId);
            if (onlyIds == null || onlyIds.isEmpty()) {
                return new PageResult<>(List.of(), 0, p.page(), p.size());
            }
        }

        String catFilter = normalizeFilter(category);
        String diffFilter = normalizeFilter(difficulty);
        String kwFilter = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        List<ProblemSet> list =
                problemSetMapper.findPublicPage(
                        catFilter, diffFilter, kwFilter, onlyIds, p.offset(), p.size());
        long total = problemSetMapper.countPublic(catFilter, diffFilter, kwFilter, onlyIds);

        List<ProblemSetSummaryVO> voList = assembleSummaryVOs(list, userId);
        return new PageResult<>(voList, total, p.page(), p.size());
    }

    /** 后台管理分页查询题集列表（ADMIN 全量，TEACHER 仅本人创建）。 */
    public PageResult<ProblemSetSummaryVO> listAdmin(
            String category, String keyword, int page, int size) {
        AuthUser user = requireTeacherOrAdmin();
        PageParam p = PageParam.of(page, size);
        Long createdBy = user.hasRole(Role.ADMIN) ? null : user.getId();
        String catFilter = normalizeFilter(category);
        String kwFilter = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        List<ProblemSet> list =
                problemSetMapper.findAdminPage(
                        catFilter, kwFilter, createdBy, p.offset(), p.size());
        long total = problemSetMapper.countAdmin(catFilter, kwFilter, createdBy);
        List<ProblemSetSummaryVO> voList = assembleSummaryVOs(list, user.getId());
        return new PageResult<>(voList, total, p.page(), p.size());
    }

    /**
     * 获取题集完整详情（含章节树、每道题 AC 状态及断点续刷推荐题）。
     *
     * <p>内置三级容灾与读时自愈（Read-Repair）：当登录用户打开题集详情时，自动核对全局三张提交表中的真实 AC 集合； 若发现历史已过题目尚未计入预聚合表，立即点亮并自动回填
     * {@code problem_set_user_progress}。
     */
    public ProblemSetDetailVO getDetail(Long setId) {
        ProblemSet ps = problemSetMapper.findById(setId);
        if (ps == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题集不存在或已删除");
        }
        AuthUser currentUser = AuthContextHolder.get();
        Long userId = currentUser != null ? currentUser.getId() : null;

        // 私有草稿仅创建者本人或管理员可见
        if (ps.getStatus() != null && ps.getStatus() == 0) {
            boolean canViewPrivate =
                    currentUser != null
                            && (currentUser.hasRole(Role.ADMIN)
                                    || Objects.equals(ps.getCreatedBy(), userId));
            if (!canViewPrivate) {
                throw new BusinessException(ResultCode.FORBIDDEN, "该题集当前为私有状态");
            }
        }

        List<ProblemSetSection> sections = sectionMapper.findBySetId(setId);
        List<ProblemSetItem> items = itemMapper.findBySetId(setId);

        // 计算当前登录用户在该题集内的真实已通过集合（Read-Repair）
        Set<Long> solvedItemIds = new LinkedHashSet<>();
        boolean favorited = false;
        Long lastItemId = null;

        if (userId != null) {
            ProblemSetUserProgress progress = progressMapper.findBySetAndUser(setId, userId);
            if (progress != null) {
                favorited = progress.getIsFavorited() != null && progress.getIsFavorited() == 1;
                lastItemId = progress.getLastItemId();
            }
            solvedItemIds = resolveAndRepairUserSolvedItems(setId, userId, items, progress);
        }

        // 转换条目视图
        List<ProblemSetItemVO> flatItemVOs = new ArrayList<>(items.size());
        for (ProblemSetItem it : items) {
            flatItemVOs.add(
                    ProblemSetItemVO.builder()
                            .id(it.getId())
                            .sectionId(it.getSectionId() != null ? it.getSectionId() : 0L)
                            .questionType(it.getQuestionType())
                            .questionId(it.getQuestionId())
                            .title(it.getTitleSnapshot())
                            .difficulty(it.getDifficultySnapshot())
                            .sourceAlive(it.getSourceAlive() == null || it.getSourceAlive() == 1)
                            .sortOrder(it.getSortOrder())
                            .solved(solvedItemIds.contains(it.getId()))
                            .build());
        }

        // 组装章节分组树
        Map<Long, List<ProblemSetItemVO>> grouped =
                flatItemVOs.stream()
                        .collect(
                                Collectors.groupingBy(
                                        it -> it.getSectionId() != null ? it.getSectionId() : 0L,
                                        LinkedHashMap::new,
                                        Collectors.toList()));

        List<ProblemSetSectionVO> sectionVOs = new ArrayList<>();
        for (ProblemSetSection sec : sections) {
            List<ProblemSetItemVO> secItems = grouped.remove(sec.getId());
            if (secItems == null) {
                secItems = List.of();
            }
            int secSolved = (int) secItems.stream().filter(Boolean.TRUE::equals).count();
            int solvedInSec = 0;
            for (ProblemSetItemVO vo : secItems) {
                if (Boolean.TRUE.equals(vo.getSolved())) solvedInSec++;
            }
            sectionVOs.add(
                    ProblemSetSectionVO.builder()
                            .id(sec.getId())
                            .title(sec.getTitle())
                            .description(sec.getDescription())
                            .sortOrder(sec.getSortOrder())
                            .totalCount(secItems.size())
                            .solvedCount(solvedInSec)
                            .items(secItems)
                            .build());
        }

        // 若存在未归属任何章节的条目（sectionId = 0 或残余），自动聚合到默认章节
        List<ProblemSetItemVO> orphanItems = new ArrayList<>();
        for (List<ProblemSetItemVO> rem : grouped.values()) {
            orphanItems.addAll(rem);
        }
        if (!orphanItems.isEmpty() || sectionVOs.isEmpty()) {
            int solvedOrphan = 0;
            for (ProblemSetItemVO vo : orphanItems) {
                if (Boolean.TRUE.equals(vo.getSolved())) solvedOrphan++;
            }
            sectionVOs.add(
                    0,
                    ProblemSetSectionVO.builder()
                            .id(0L)
                            .title(sectionVOs.isEmpty() ? "精选题目列表" : "综合练习")
                            .description("按推荐顺序依次攻克")
                            .sortOrder(0)
                            .totalCount(orphanItems.size())
                            .solvedCount(solvedOrphan)
                            .items(orphanItems)
                            .build());
        }

        // 计算「继续刷题」推荐锚点：优先取上次停留且未失效的题目，否则取第一道未 AC 的有效题目
        ProblemSetItemVO resumeItem = null;
        if (lastItemId != null) {
            final Long targetLast = lastItemId;
            resumeItem =
                    flatItemVOs.stream()
                            .filter(
                                    it ->
                                            Objects.equals(it.getId(), targetLast)
                                                    && Boolean.TRUE.equals(it.getSourceAlive()))
                            .findFirst()
                            .orElse(null);
        }
        if (resumeItem == null) {
            resumeItem =
                    flatItemVOs.stream()
                            .filter(
                                    it ->
                                            Boolean.TRUE.equals(it.getSourceAlive())
                                                    && !Boolean.TRUE.equals(it.getSolved()))
                            .findFirst()
                            .orElse(
                                    flatItemVOs.stream()
                                            .filter(it -> Boolean.TRUE.equals(it.getSourceAlive()))
                                            .findFirst()
                                            .orElse(null));
        }

        String authorName = resolveAuthorName(ps.getCreatedBy());

        return ProblemSetDetailVO.builder()
                .id(ps.getId())
                .title(ps.getTitle())
                .subtitle(ps.getSubtitle())
                .description(ps.getDescription())
                .category(ps.getCategory())
                .difficulty(ps.getDifficulty())
                .tags(parseTags(ps.getTags()))
                .itemCount(ps.getItemCount())
                .algoCount(ps.getAlgoCount())
                .dbCount(ps.getDbCount())
                .objectiveCount(ps.getObjectiveCount())
                .participantCount(ps.getParticipantCount())
                .isOfficial(ps.getIsOfficial())
                .status(ps.getStatus())
                .version(ps.getVersion())
                .createdBy(ps.getCreatedBy())
                .authorName(authorName)
                .createdAt(ps.getCreatedAt())
                .updatedAt(ps.getUpdatedAt())
                .favorited(favorited)
                .solvedCount(solvedItemIds.size())
                .lastItemId(resumeItem != null ? resumeItem.getId() : lastItemId)
                .resumeItem(resumeItem)
                .sections(sectionVOs)
                .flatItems(flatItemVOs)
                .build();
    }

    /** 收藏 / 取消收藏题集（幂等切换）。 */
    @Transactional
    public Map<String, Object> toggleFavorite(Long setId) {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        ProblemSet ps = problemSetMapper.findById(setId);
        if (ps == null || ps.getStatus() == -1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题集不存在");
        }
        ProblemSetUserProgress current = progressMapper.findBySetAndUser(setId, userId);
        boolean wasFav =
                current != null
                        && current.getIsFavorited() != null
                        && current.getIsFavorited() == 1;
        int nextFav = wasFav ? 0 : 1;
        progressMapper.upsertFavorite(setId, userId, nextFav);
        problemSetMapper.adjustParticipantCount(setId, wasFav ? -1 : 1);

        int latestCount =
                Math.max(
                        0,
                        (ps.getParticipantCount() != null ? ps.getParticipantCount() : 0)
                                + (wasFav ? -1 : 1));
        return Map.of("favorited", nextFav == 1, "participantCount", latestCount);
    }

    /** 记录用户最近练习的题目条目（用于「继续刷题」断点定位）。 */
    public void touchLastItem(Long setId, Long itemId) {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null || setId == null || itemId == null) {
            return;
        }
        try {
            progressMapper.upsertLastItem(setId, userId, itemId);
        } catch (Exception e) {
            log.warn(
                    "更新题集断点续刷位置失败 setId={}, userId={}, itemId={}: {}",
                    setId,
                    userId,
                    itemId,
                    e.getMessage());
        }
    }

    /**
     * 当用户在任何场景（普通题库、题集内）成功 AC 一道题目时触发，自动同步所有包含该题目的题集进度。
     *
     * <p>容错隔离：任何异常内部消化，绝不影响主判题与提交事务。
     */
    public void onQuestionSolved(Long userId, String questionType, Long questionId) {
        if (userId == null || questionType == null || questionId == null) {
            return;
        }
        try {
            String normalizedType = normalizeQuestionType(questionType);
            List<ProblemSetItem> matchedItems =
                    itemMapper.findByQuestion(normalizedType, questionId);
            if (matchedItems == null || matchedItems.isEmpty()) {
                return;
            }
            for (ProblemSetItem item : matchedItems) {
                Long setId = item.getSetId();
                ProblemSetUserProgress progress = progressMapper.findBySetAndUser(setId, userId);
                Set<Long> solvedIds =
                        parseJsonIdSet(progress != null ? progress.getSolvedItemIds() : null);
                if (solvedIds.add(item.getId())) {
                    progressMapper.upsertProgress(
                            setId, userId, solvedIds.size(), writeJson(solvedIds));
                }
            }
        } catch (Exception e) {
            log.warn(
                    "实时同步题集进度失败 userId={}, type={}, questionId={}: {}",
                    userId,
                    questionType,
                    questionId,
                    e.getMessage());
        }
    }

    /** 创建新题集（TEACHER / ADMIN）。 */
    @Transactional
    public ProblemSetDetailVO createProblemSet(SaveProblemSetRequest req) {
        AuthUser user = requireTeacherOrAdmin();
        boolean isAdmin = user.hasRole(Role.ADMIN);

        ProblemSet ps =
                ProblemSet.builder()
                        .title(req.getTitle().trim())
                        .subtitle(trimOrNull(req.getSubtitle()))
                        .description(trimOrNull(req.getDescription()))
                        .category(normalizeCategory(req.getCategory()))
                        .difficulty(normalizeDifficulty(req.getDifficulty()))
                        .tags(trimOrNull(req.getTags()))
                        .itemCount(0)
                        .algoCount(0)
                        .dbCount(0)
                        .objectiveCount(0)
                        .participantCount(0)
                        .isOfficial(
                                isAdmin && req.getIsOfficial() != null ? req.getIsOfficial() : 0)
                        .status(req.getStatus() != null ? req.getStatus() : 1)
                        .version(1)
                        .createdBy(user.getId())
                        .build();

        problemSetMapper.insert(ps);
        Counts counts = saveSectionsAndItems(ps.getId(), req);

        ps.setItemCount(counts.total());
        ps.setAlgoCount(counts.algo());
        ps.setDbCount(counts.db());
        ps.setObjectiveCount(counts.objective());
        problemSetMapper.updateWithVersion(ps);
        evictCache(ps.getId());

        return getDetail(ps.getId());
    }

    /** 更新题集（基于乐观锁并发控制）。 */
    @Transactional
    public ProblemSetDetailVO updateProblemSet(Long setId, SaveProblemSetRequest req) {
        AuthUser user = requireTeacherOrAdmin();
        ProblemSet existing = problemSetMapper.findById(setId);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题集不存在");
        }
        boolean isAdmin = user.hasRole(Role.ADMIN);
        if (!isAdmin && !Objects.equals(existing.getCreatedBy(), user.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅创建者本人或管理员可编辑该题集");
        }

        sectionMapper.deleteBySetId(setId);
        itemMapper.deleteBySetId(setId);
        Counts counts = saveSectionsAndItems(setId, req);

        existing.setTitle(req.getTitle().trim());
        existing.setSubtitle(trimOrNull(req.getSubtitle()));
        existing.setDescription(trimOrNull(req.getDescription()));
        existing.setCategory(normalizeCategory(req.getCategory()));
        existing.setDifficulty(normalizeDifficulty(req.getDifficulty()));
        existing.setTags(trimOrNull(req.getTags()));
        existing.setItemCount(counts.total());
        existing.setAlgoCount(counts.algo());
        existing.setDbCount(counts.db());
        existing.setObjectiveCount(counts.objective());
        if (isAdmin && req.getIsOfficial() != null) {
            existing.setIsOfficial(req.getIsOfficial());
        }
        if (req.getStatus() != null) {
            existing.setStatus(req.getStatus());
        }
        if (req.getVersion() != null) {
            existing.setVersion(req.getVersion());
        }

        int updated = problemSetMapper.updateWithVersion(existing);
        if (updated == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题集已被其他窗口更新，请刷新后重试");
        }
        evictCache(setId);
        return getDetail(setId);
    }

    /** 软删除题集。 */
    @Transactional
    public void deleteProblemSet(Long setId) {
        AuthUser user = requireTeacherOrAdmin();
        ProblemSet existing = problemSetMapper.findById(setId);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题集不存在");
        }
        if (!user.hasRole(Role.ADMIN) && !Objects.equals(existing.getCreatedBy(), user.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅创建者本人或管理员可删除该题集");
        }
        problemSetMapper.softDelete(setId);
        evictCache(setId);
    }

    // =========================================================================
    // 内部辅助方法：读自愈、章节条目持久化、缓存容错
    // =========================================================================

    private Set<Long> resolveAndRepairUserSolvedItems(
            Long setId,
            Long userId,
            List<ProblemSetItem> items,
            ProblemSetUserProgress existingProgress) {
        if (items == null || items.isEmpty()) {
            return Set.of();
        }
        try {
            boolean hasAlgo = false;
            boolean hasDb = false;
            boolean hasObj = false;
            for (ProblemSetItem it : items) {
                if ("ALGO".equalsIgnoreCase(it.getQuestionType())) hasAlgo = true;
                else if ("DB".equalsIgnoreCase(it.getQuestionType())) hasDb = true;
                else if ("OBJECTIVE".equalsIgnoreCase(it.getQuestionType())) hasObj = true;
            }

            Set<Long> acAlgoIds =
                    hasAlgo
                            ? new HashSet<>(submissionMapper.findAcceptedQuestionIds(userId))
                            : Set.of();
            Set<Long> acDbIds =
                    hasDb
                            ? new HashSet<>(dbSubmissionMapper.findAcceptedQuestionIds(userId))
                            : Set.of();
            Set<Long> acObjIds =
                    hasObj
                            ? new HashSet<>(
                                    objectiveSubmissionMapper.findCorrectQuestionIds(userId))
                            : Set.of();

            Set<Long> solvedItemIds = new LinkedHashSet<>();
            for (ProblemSetItem it : items) {
                String t = it.getQuestionType() != null ? it.getQuestionType().toUpperCase() : "";
                Long qid = it.getQuestionId();
                if ("ALGO".equals(t) && acAlgoIds.contains(qid)) {
                    solvedItemIds.add(it.getId());
                } else if ("DB".equals(t) && acDbIds.contains(qid)) {
                    solvedItemIds.add(it.getId());
                } else if ("OBJECTIVE".equals(t) && acObjIds.contains(qid)) {
                    solvedItemIds.add(it.getId());
                }
            }

            int oldCount =
                    existingProgress != null && existingProgress.getSolvedCount() != null
                            ? existingProgress.getSolvedCount()
                            : -1;
            if (solvedItemIds.size() != oldCount
                    && (!solvedItemIds.isEmpty() || existingProgress != null)) {
                progressMapper.upsertProgress(
                        setId, userId, solvedItemIds.size(), writeJson(solvedItemIds));
            }
            return solvedItemIds;
        } catch (Exception e) {
            log.warn(
                    "题集进度读自愈对账异常，降级使用预聚合快照 setId={}, userId={}: {}", setId, userId, e.getMessage());
            return parseJsonIdSet(
                    existingProgress != null ? existingProgress.getSolvedItemIds() : null);
        }
    }

    private record Counts(int total, int algo, int db, int objective) {}

    private Counts saveSectionsAndItems(Long setId, SaveProblemSetRequest req) {
        List<ProblemSetItem> toInsert = new ArrayList<>();
        Set<String> dedupKeys = new HashSet<>();
        int algoCount = 0;
        int dbCount = 0;
        int objCount = 0;
        int globalOrder = 1;

        if (req.getSections() != null && !req.getSections().isEmpty()) {
            int secOrder = 1;
            for (SaveProblemSetRequest.SectionInput secIn : req.getSections()) {
                if (secIn == null || secIn.getTitle() == null || secIn.getTitle().isBlank()) {
                    continue;
                }
                ProblemSetSection sec =
                        ProblemSetSection.builder()
                                .setId(setId)
                                .title(secIn.getTitle().trim())
                                .description(trimOrNull(secIn.getDescription()))
                                .sortOrder(
                                        secIn.getSortOrder() != null
                                                ? secIn.getSortOrder()
                                                : secOrder++)
                                .build();
                sectionMapper.insert(sec);

                if (secIn.getItems() != null) {
                    for (SaveProblemSetRequest.ItemInput itemIn : secIn.getItems()) {
                        ProblemSetItem built =
                                buildItemEntity(
                                        setId, sec.getId(), itemIn, globalOrder++, dedupKeys);
                        if (built != null) {
                            toInsert.add(built);
                            if ("ALGO".equals(built.getQuestionType())) algoCount++;
                            else if ("DB".equals(built.getQuestionType())) dbCount++;
                            else if ("OBJECTIVE".equals(built.getQuestionType())) objCount++;
                        }
                    }
                }
            }
        }

        if (req.getItems() != null && !req.getItems().isEmpty()) {
            for (SaveProblemSetRequest.ItemInput itemIn : req.getItems()) {
                ProblemSetItem built = buildItemEntity(setId, 0L, itemIn, globalOrder++, dedupKeys);
                if (built != null) {
                    toInsert.add(built);
                    if ("ALGO".equals(built.getQuestionType())) algoCount++;
                    else if ("DB".equals(built.getQuestionType())) dbCount++;
                    else if ("OBJECTIVE".equals(built.getQuestionType())) objCount++;
                }
            }
        }

        if (!toInsert.isEmpty()) {
            itemMapper.insertBatch(toInsert);
        }
        return new Counts(toInsert.size(), algoCount, dbCount, objCount);
    }

    private ProblemSetItem buildItemEntity(
            Long setId,
            Long sectionId,
            SaveProblemSetRequest.ItemInput itemIn,
            int defaultOrder,
            Set<String> dedupKeys) {
        if (itemIn == null || itemIn.getQuestionId() == null) {
            return null;
        }
        String qType = normalizeQuestionType(itemIn.getQuestionType());
        String uk = qType + ":" + itemIn.getQuestionId();
        if (!dedupKeys.add(uk)) {
            return null;
        }

        String title = trimOrNull(itemIn.getTitle());
        String difficulty = trimOrNull(itemIn.getDifficulty());
        int alive = 1;

        try {
            QuestionTypeStrategy strategy = strategyFactory.getStrategy(qType);
            QuestionMeta meta = strategy.resolveQuestionMeta(itemIn.getQuestionId());
            if (meta != null) {
                if (title == null) title = meta.getTitle();
                if (difficulty == null) difficulty = meta.getDifficulty();
            }
        } catch (Exception e) {
            alive = 0;
            if (title == null) {
                title = "题目 #" + itemIn.getQuestionId();
            }
        }

        return ProblemSetItem.builder()
                .setId(setId)
                .sectionId(sectionId != null ? sectionId : 0L)
                .questionType(qType)
                .questionId(itemIn.getQuestionId())
                .titleSnapshot(title != null ? title : ("题目 #" + itemIn.getQuestionId()))
                .difficultySnapshot(difficulty != null ? difficulty : "MEDIUM")
                .sourceAlive(alive)
                .sortOrder(itemIn.getSortOrder() != null ? itemIn.getSortOrder() : defaultOrder)
                .build();
    }

    private List<ProblemSetSummaryVO> assembleSummaryVOs(List<ProblemSet> sets, Long userId) {
        if (sets == null || sets.isEmpty()) {
            return List.of();
        }
        List<Long> setIds = sets.stream().map(ProblemSet::getId).toList();
        Map<Long, ProblemSetUserProgress> progressMap = new HashMap<>();
        if (userId != null && !setIds.isEmpty()) {
            List<ProblemSetUserProgress> progList =
                    progressMapper.findByUserAndSetIds(userId, setIds);
            if (progList != null) {
                for (ProblemSetUserProgress p : progList) {
                    progressMap.put(p.getSetId(), p);
                }
            }
        }

        List<ProblemSetSummaryVO> result = new ArrayList<>(sets.size());
        for (ProblemSet ps : sets) {
            ProblemSetUserProgress prog = progressMap.get(ps.getId());
            result.add(
                    ProblemSetSummaryVO.builder()
                            .id(ps.getId())
                            .title(ps.getTitle())
                            .subtitle(ps.getSubtitle())
                            .description(ps.getDescription())
                            .category(ps.getCategory())
                            .difficulty(ps.getDifficulty())
                            .tags(parseTags(ps.getTags()))
                            .itemCount(ps.getItemCount())
                            .algoCount(ps.getAlgoCount())
                            .dbCount(ps.getDbCount())
                            .objectiveCount(ps.getObjectiveCount())
                            .participantCount(ps.getParticipantCount())
                            .isOfficial(ps.getIsOfficial())
                            .status(ps.getStatus())
                            .version(ps.getVersion())
                            .createdBy(ps.getCreatedBy())
                            .authorName(resolveAuthorName(ps.getCreatedBy()))
                            .createdAt(ps.getCreatedAt())
                            .updatedAt(ps.getUpdatedAt())
                            .favorited(
                                    prog != null
                                            && prog.getIsFavorited() != null
                                            && prog.getIsFavorited() == 1)
                            .solvedCount(
                                    prog != null && prog.getSolvedCount() != null
                                            ? prog.getSolvedCount()
                                            : 0)
                            .lastItemId(prog != null ? prog.getLastItemId() : null)
                            .build());
        }
        return result;
    }

    private void evictCache(Long setId) {
        if (setId == null) return;
        try {
            long jitter = ThreadLocalRandom.current().nextLong(0, 300);
            redis.delete(CACHE_SKELETON_PREFIX + setId);
            log.debug("已清理题集缓存 setId={}, nextJitter={}s", setId, jitter);
        } catch (Exception e) {
            log.warn("Redis 题集缓存清理异常（已自动降级忽略）setId={}: {}", setId, e.getMessage());
        }
    }

    private AuthUser requireTeacherOrAdmin() {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!user.hasRole(Role.ADMIN) && !user.hasRole(Role.TEACHER)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return user;
    }

    private String resolveAuthorName(Long userId) {
        if (userId == null) return "KECE 官方教研组";
        try {
            User u = userMapper.findById(userId);
            return u != null && u.getUsername() != null ? u.getUsername() : "KECE 教研组";
        } catch (Exception e) {
            return "KECE 教研组";
        }
    }

    private List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private Set<Long> parseJsonIdSet(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            List<Long> list = objectMapper.readValue(json, new TypeReference<List<Long>>() {});
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String normalizeQuestionType(String raw) {
        if (raw == null || raw.isBlank()) return "ALGO";
        String upper = raw.trim().toUpperCase();
        if ("DATABASE".equals(upper) || "SQL".equals(upper)) return "DB";
        if ("QUIZ".equals(upper) || "OBJ".equals(upper)) return "OBJECTIVE";
        return upper;
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return "MIXED";
        String upper = raw.trim().toUpperCase();
        if (Set.of("ALGO", "DB", "OBJECTIVE", "MIXED", "COURSE").contains(upper)) {
            return upper;
        }
        return "MIXED";
    }

    private String normalizeDifficulty(String raw) {
        if (raw == null || raw.isBlank()) return "MEDIUM";
        String upper = raw.trim().toUpperCase();
        if (Set.of("EASY", "MEDIUM", "HARD").contains(upper)) {
            return upper;
        }
        return "MEDIUM";
    }

    private String normalizeFilter(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return raw.trim().toUpperCase();
    }

    private String trimOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim();
    }
}
