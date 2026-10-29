package com.kece.database.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.admin.dto.RejudgeResultVO;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.database.dto.DbSubmissionListVO;
import com.kece.database.entity.DbQuestion;
import com.kece.database.entity.DbSubmission;
import com.kece.database.entity.DbTestCase;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.database.mapper.DbTestCaseMapper;
import com.kece.problemset.service.ProblemSetService;
import com.kece.submission.dto.ProgressVO;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.enums.SubmissionStatus;
import com.kece.user.service.UserDailyAcService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据库提交与评测业务逻辑：压队列、Redis 状态缓存、结果落库。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbSubmissionService {

    private static final String QUEUE_KEY = "judge:sql:queue";
    private static final String RESULTS_KEY = "judge:sql:results";
    private static final String STATUS_KEY_PREFIX = "dbsub:status:";
    private static final String OWNER_KEY_PREFIX = "dbsub:owner:";
    private static final long STATUS_TTL_SECONDS = 3600;
    private static final int SQL_TIME_LIMIT_MS = 5000;

    private final DbSubmissionMapper submissionMapper;
    private final DbQuestionMapper questionMapper;
    private final DbTestCaseMapper testCaseMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserDailyAcService userDailyAcService;
    private final ProblemSetService problemSetService;

    /** 提交 SQL：入库、压队列、写 Redis 初始状态，返回任务 ID 与 PENDING。 */
    @Transactional
    public SubmissionVO submit(Long questionId, String sql) {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "SQL 不能为空");
        }
        DbQuestion question = questionMapper.findById(questionId);
        if (question == null || question.getStatus() == null || question.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在或未公开");
        }

        DbSubmission submission = new DbSubmission();
        submission.setUserId(userId);
        submission.setQuestionId(questionId);
        submission.setDialect(question.getDialect());
        submission.setSqlContent(trimmed);
        submission.setStatus(SubmissionStatus.PENDING.name());
        submissionMapper.insert(submission);

        List<DbTestCase> testCases = testCaseMapper.findByQuestionId(questionId);
        String taskJson = writeJson(buildTask(submission, question, testCases, trimmed));

        SubmissionVO pending = buildPendingVO(submission.getId(), testCases.size());
        redis.opsForValue()
                .set(
                        statusKey(submission.getId()),
                        writeJson(pending),
                        Duration.ofSeconds(STATUS_TTL_SECONDS));
        redis.opsForValue()
                .set(
                        ownerKey(submission.getId()),
                        String.valueOf(userId),
                        Duration.ofSeconds(STATUS_TTL_SECONDS));
        redis.opsForList().rightPush(QUEUE_KEY, taskJson);

        return pending;
    }

    /** 数据库提交记录列表：默认本人提交，可指定 targetUserId 查看该用户提交（非本人自动脱敏 SQL 原文），按 id 倒序分页。 */
    public PageResult<DbSubmissionListVO> list(
            Long targetUserId, String status, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = targetUserId != null ? targetUserId : user.getId();
        PageParam p = PageParam.of(page, size);
        String statusFilter =
                (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        List<DbSubmissionListVO> list =
                submissionMapper.findPage(userId, null, statusFilter, null, p.offset(), p.size());
        // 安全防抄袭：前台数据库提交列表仅提交者本人可见 SQL 原文，其他人一律脱敏置空
        if (!userId.equals(user.getId())) {
            for (DbSubmissionListVO item : list) {
                item.setSqlContent(null);
            }
        }
        long total = submissionMapper.count(userId, null, statusFilter, null);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    public PageResult<DbSubmissionListVO> list(String status, int page, int size) {
        return list(null, status, page, size);
    }

    /** 后台数据库提交列表：ADMIN 可跨用户/跨题查询；TEACHER 仅限自己所出题目的提交（仍可筛任意用户，归属约束在 SQL createdBy 条件）。 */
    public PageResult<DbSubmissionListVO> listAdmin(
            String status, Long userId, Long questionId, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        PageParam p = PageParam.of(page, size);
        String statusFilter =
                (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        Long targetCreatedBy = user.hasRole(Role.ADMIN) ? null : user.getId();
        List<DbSubmissionListVO> list =
                submissionMapper.findPage(
                        userId, questionId, statusFilter, targetCreatedBy, p.offset(), p.size());
        long total = submissionMapper.count(userId, questionId, statusFilter, targetCreatedBy);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    /** 当前用户已 AC 的数据库题目 id（去重），供题目列表标记「已通过」。 */
    public List<Long> solvedQuestionIds() {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return submissionMapper.findAcceptedQuestionIds(userId);
    }

    /** 查询评测状态与结果：优先命中 Redis 状态缓存，缺失时回退 MySQL。 */
    public SubmissionVO get(Long id) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        DbSubmission submission = null;
        String ownerId = redis.opsForValue().get(ownerKey(id));
        if (ownerId != null) {
            boolean isOwnerOrAdmin =
                    user.hasRole(Role.ADMIN) || ownerId.equals(String.valueOf(user.getId()));
            // 非提交人且非教师/管理员：无需查库即可直接拒绝
            if (!isOwnerOrAdmin && !user.hasRole(Role.TEACHER)) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
            String cached = redis.opsForValue().get(statusKey(id));
            if (cached != null) {
                SubmissionVO cachedVo = readJson(cached, SubmissionVO.class);
                // 轮询快路径：非终态（PENDING/COMPILING/JUDGING）无测试点详情，提交人或管理员直接从 Redis 返回，免查 MySQL
                if (isOwnerOrAdmin
                        && cachedVo != null
                        && cachedVo.getStatus() != null
                        && !SubmissionStatus.from(cachedVo.getStatus()).isTerminal()) {
                    return cachedVo;
                }
                submission = submissionMapper.findById(id);
                if (submission == null || !canAccessSubmission(user, submission)) {
                    throw new BusinessException(ResultCode.FORBIDDEN);
                }
                return sanitize(cachedVo, user, submission.getQuestionId());
            }
        }
        if (submission == null) {
            submission = submissionMapper.findById(id);
            if (submission == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "提交记录不存在");
            }
        }
        if (!canAccessSubmission(user, submission)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return sanitize(toVOFromEntity(submission), user, submission.getQuestionId());
    }

    /** 样例豁免统一（决策 27）：样例测试点的数据本就随题面公开，其比对结果始终可见；非样例仅管理员或该题出题人可见， 其余答题者置空，避免由比对结果反推隐藏测试点的数据与答案。 */
    private SubmissionVO sanitize(SubmissionVO vo, AuthUser user, Long questionId) {
        if (vo == null || vo.getResult() == null) {
            return vo;
        }
        var report = vo.getResult().getTestCasesReport();
        if (report == null || report.isEmpty()) {
            return vo;
        }
        boolean privileged = isPrivilegedForQuestion(user, questionId);
        Set<Long> sampleCaseIds = findSampleCaseIds(questionId);
        report.forEach(
                c -> {
                    if (sampleCaseIds.contains(c.getCaseId()) || privileged) {
                        return;
                    }
                    c.setExpectedOutput(null);
                    c.setActualOutput(null);
                });
        return vo;
    }

    /** 管理员或该题出题人可见隐藏用例。 */
    private boolean isPrivilegedForQuestion(AuthUser user, Long questionId) {
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        DbQuestion question = questionId == null ? null : questionMapper.findById(questionId);
        return question != null && question.getCreatedBy().equals(user.getId());
    }

    /**
     * 重判单条数据库提交：仅管理员或该题出题人可操作；评测中的提交拒绝；使用题目当前数据重新入队。
     *
     * <p>三步缺一不可：reset DB（专用 SQL 置回 NULL）→ 清/刷 Redis 状态与 owner（成对）→ 重新压队列任务。
     */
    @Transactional
    public RejudgeResultVO rejudge(Long id) {
        DbSubmission submission = submissionMapper.findById(id);
        if (submission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提交记录不存在");
        }
        checkRejudgePermission(submission);
        if (isJudging(submission.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该提交正在评测中，请稍后再试");
        }
        DbQuestion question = questionMapper.findById(submission.getQuestionId());
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在或已删除");
        }
        List<DbTestCase> testCases = testCaseMapper.findByQuestionId(submission.getQuestionId());

        submissionMapper.resetResult(id);
        SubmissionVO pending = buildPendingVO(id, testCases.size());
        redis.opsForValue()
                .set(statusKey(id), writeJson(pending), Duration.ofSeconds(STATUS_TTL_SECONDS));
        redis.opsForValue()
                .set(
                        ownerKey(id),
                        String.valueOf(submission.getUserId()),
                        Duration.ofSeconds(STATUS_TTL_SECONDS));
        redis.opsForList()
                .rightPush(
                        QUEUE_KEY,
                        writeJson(
                                buildTask(
                                        submission,
                                        question,
                                        testCases,
                                        submission.getSqlContent())));
        return RejudgeResultVO.builder()
                .submissionId(id)
                .status(SubmissionStatus.PENDING.name())
                .build();
    }

    private void checkRejudgePermission(DbSubmission submission) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN)) {
            return;
        }
        DbQuestion question = questionMapper.findById(submission.getQuestionId());
        if (question == null || !question.getCreatedBy().equals(user.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /** 评测中（非终态中的进行态）不允许重判。 */
    private boolean isJudging(String status) {
        return "PENDING".equals(status) || "COMPILING".equals(status) || "JUDGING".equals(status);
    }

    /** 该提交所属题目的样例测试点 id；题目已删除或查不到时返回空集合（等价于全部隐藏）。 */
    private Set<Long> findSampleCaseIds(Long questionId) {
        if (questionId == null) {
            return Set.of();
        }
        List<Long> ids = testCaseMapper.findSampleCaseIds(questionId);
        return ids == null || ids.isEmpty() ? Set.of() : new HashSet<>(ids);
    }

    /** 定时回写：将判题机写入 Redis 的终态结果落库 MySQL（幂等）。 */
    public void collectResults() {
        try {
            while (true) {
                String submissionIdStr = redis.opsForList().leftPop(RESULTS_KEY);
                if (submissionIdStr == null) {
                    break;
                }
                try {
                    persistResult(Long.valueOf(submissionIdStr));
                } catch (Exception e) {
                    log.warn("回写数据库评测结果失败 submissionId={}: {}", submissionIdStr, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("回写任务扫描跳过: {}", e.getMessage());
        }
    }

    public void persistResult(Long submissionId) {
        String cached = redis.opsForValue().get(statusKey(submissionId));
        if (cached == null) {
            return;
        }
        SubmissionVO vo = readJson(cached, SubmissionVO.class);
        if (vo == null
                || vo.getStatus() == null
                || !SubmissionStatus.from(vo.getStatus()).isTerminal()) {
            return;
        }
        SubmissionResultVO result = vo.getResult();
        DbSubmission sub = submissionMapper.findById(submissionId);
        boolean newlyAccepted =
                sub != null
                        && !"ACCEPTED".equals(sub.getStatus())
                        && SubmissionStatus.ACCEPTED.name().equals(vo.getStatus());

        submissionMapper.updateResult(
                submissionId,
                vo.getStatus(),
                result == null ? null : result.getScore(),
                result == null ? null : result.getTimeUsedMs(),
                result == null ? null : writeJson(result));

        if (newlyAccepted) {
            LocalDate date =
                    sub.getCreatedAt() != null ? sub.getCreatedAt().toLocalDate() : LocalDate.now();
            userDailyAcService.recordDbAc(sub.getUserId(), date);
            problemSetService.onQuestionSolved(sub.getUserId(), "DB", sub.getQuestionId());
        }
    }

    private Map<String, Object> buildTask(
            DbSubmission submission, DbQuestion question, List<DbTestCase> testCases, String sql) {
        Map<String, Object> task = new HashMap<>();
        task.put("submissionId", submission.getId());
        task.put("questionId", submission.getQuestionId());
        task.put("type", "sql");
        task.put("dialect", question.getDialect());
        // 建表语句与标准答案在题目级，所有测试点共用；测试点只带各自的一套数据。
        task.put("schemaSql", question.getSchemaSql());
        task.put("referenceSql", question.getReferenceSql());
        task.put("verifySql", question.getVerifySql());
        task.put(
                "orderSensitive",
                question.getOrderSensitive() != null && question.getOrderSensitive() == 1);
        task.put("codeContent", Map.of("answer.sql", sql));
        task.put("timeLimitMs", SQL_TIME_LIMIT_MS);
        List<Map<String, Object>> cases =
                testCases.stream()
                        .map(
                                tc -> {
                                    Map<String, Object> c = new HashMap<>();
                                    c.put("caseId", tc.getId());
                                    c.put("seedSql", tc.getSeedSql());
                                    return c;
                                })
                        .toList();
        task.put("testCases", cases);
        return task;
    }

    private SubmissionVO buildPendingVO(Long id, int totalCases) {
        return SubmissionVO.builder()
                .submissionId(id)
                .status(SubmissionStatus.PENDING.name())
                .progress(ProgressVO.builder().totalCases(totalCases).finishedCases(0).build())
                .build();
    }

    private SubmissionVO toVOFromEntity(DbSubmission s) {
        SubmissionResultVO result = null;
        if (s.getResultJson() != null && !s.getResultJson().isBlank()) {
            result = readJson(s.getResultJson(), SubmissionResultVO.class);
        }
        return SubmissionVO.builder()
                .submissionId(s.getId())
                .status(s.getStatus())
                .result(result)
                .build();
    }

    /** 提交可见性：管理员、提交人本人、该题出题人均可查看。 */
    private boolean canAccessSubmission(AuthUser user, DbSubmission submission) {
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        if (submission.getUserId().equals(user.getId())) {
            return true;
        }
        DbQuestion question = questionMapper.findById(submission.getQuestionId());
        return question != null && question.getCreatedBy().equals(user.getId());
    }

    private String statusKey(Long id) {
        return STATUS_KEY_PREFIX + id;
    }

    private String ownerKey(Long id) {
        return OWNER_KEY_PREFIX + id;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "JSON 序列化失败");
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
