package com.kece.submission.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.admin.dto.RejudgeResultVO;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.PageParam;
import com.kece.common.response.PageResult;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.common.security.Role;
import com.kece.question.entity.Question;
import com.kece.question.entity.TestCase;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.mapper.TestCaseMapper;
import com.kece.submission.dto.ProgressVO;
import com.kece.submission.dto.SubmissionListVO;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.dto.SubmitRequest;
import com.kece.submission.entity.Submission;
import com.kece.submission.enums.SubmissionStatus;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.user.service.UserDailyAcService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提交与评测业务逻辑：压队列、Redis 状态缓存、结果落库。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    private static final String QUEUE_KEY = "judge:queue";
    private static final String RESULTS_KEY = "judge:results";
    private static final String STATUS_KEY_PREFIX = "sub:status:";
    private static final String OWNER_KEY_PREFIX = "sub:owner:";
    private static final long STATUS_TTL_SECONDS = 3600;
    private static final List<String> SUPPORTED_LANGUAGES =
            List.of("cpp", "java", "python", "arkts");

    private final SubmissionMapper submissionMapper;
    private final QuestionMapper questionMapper;
    private final TestCaseMapper testCaseMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserDailyAcService userDailyAcService;

    /** 提交代码：入库、压队列、写 Redis 初始状态，返回任务 ID 与 PENDING。 */
    @Transactional
    public SubmissionVO submit(SubmitRequest request) {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String language = request.getLanguage().trim().toLowerCase();
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的语言: " + request.getLanguage());
        }
        Question question = questionMapper.findById(request.getQuestionId());
        if (question == null || question.getStatus() == null || question.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在或未公开");
        }
        if (!supportsLanguage(question, language)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题目不支持此语言");
        }
        Map<String, String> code = normalizeCode(request.getCodeContent());
        if (code.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "代码内容不能为空");
        }

        Submission submission = new Submission();
        submission.setUserId(userId);
        submission.setQuestionId(request.getQuestionId());
        submission.setContestId(request.getContestId());
        submission.setLanguage(language);
        submission.setCodeContent(writeJson(code));
        submission.setStatus(SubmissionStatus.PENDING.name());
        submissionMapper.insert(submission);

        List<TestCase> testCases = testCaseMapper.findByQuestionId(request.getQuestionId());
        String taskJson = writeJson(buildTask(submission, question, testCases, code));

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

    /** 提交列表：默认本人提交，可指定 targetUserId 查看该用户提交，按 id 倒序分页。 */
    public PageResult<SubmissionListVO> list(Long targetUserId, String status, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (targetUserId != null && !targetUserId.equals(user.getId()) && !user.hasRole(Role.ADMIN) && !user.hasRole(Role.TEACHER)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看其他用户的提交记录");
        }
        Long userId = targetUserId != null ? targetUserId : user.getId();
        PageParam p = PageParam.of(page, size);
        String statusFilter =
                (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        List<SubmissionListVO> list =
                submissionMapper.findPage(userId, null, statusFilter, null, p.offset(), p.size());
        // 安全防抄袭：前台提交列表仅提交者本人可见代码原文，其他人一律脱敏置空
        if (!userId.equals(user.getId())) {
            for (SubmissionListVO item : list) {
                item.setCodeContent(null);
            }
        }
        long total = submissionMapper.count(userId, null, statusFilter, null);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    public PageResult<SubmissionListVO> list(String status, int page, int size) {
        return list(null, status, page, size);
    }

    /** 后台提交列表：ADMIN 可跨用户/跨题查询；TEACHER 仅限自己所出题目的提交（仍可筛任意用户，归属约束在 SQL createdBy 条件）。 */
    public PageResult<SubmissionListVO> listAdmin(
            String status, Long userId, Long questionId, int page, int size) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        PageParam p = PageParam.of(page, size);
        String statusFilter =
                (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        Long targetCreatedBy = user.hasRole(Role.ADMIN) ? null : user.getId();
        List<SubmissionListVO> list =
                submissionMapper.findPage(
                        userId, questionId, statusFilter, targetCreatedBy, p.offset(), p.size());
        long total = submissionMapper.count(userId, questionId, statusFilter, targetCreatedBy);
        return new PageResult<>(list, total, p.page(), p.size());
    }

    /** 当前用户已 AC 的题目 id（去重），供题目列表标记「已通过」。 */
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
        Submission submission = null;
        String ownerId = redis.opsForValue().get(ownerKey(id));
        if (ownerId != null) {
            // 快路径：提交人 + 题目归属双重判定（出题人可看自己所出题目的提交）
            submission = submissionMapper.findById(id);
            if (submission == null || !canAccessSubmission(user, submission)) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
            String cached = redis.opsForValue().get(statusKey(id));
            if (cached != null) {
                return sanitize(
                        readJson(cached, SubmissionVO.class), user, submission.getQuestionId());
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

    /** 样例豁免统一（决策 27）：样例用例的期望/实际输出始终可见（本就随题面公开）；非样例仅管理员或该题出题人可见， 其余答题者置空，避免由比对结果反推隐藏测试点的数据与答案。 */
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

    private Set<Long> findSampleCaseIds(Long questionId) {
        List<TestCase> samples = testCaseMapper.findSamplesByQuestionId(questionId);
        if (samples == null || samples.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (TestCase tc : samples) {
            ids.add(tc.getId());
        }
        return ids;
    }

    /** 管理员或该题出题人可见隐藏用例。 */
    private boolean isPrivilegedForQuestion(AuthUser user, Long questionId) {
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        Question question = questionId == null ? null : questionMapper.findById(questionId);
        return question != null && question.getCreatedBy().equals(user.getId());
    }

    /**
     * 重判单条提交：仅管理员或该题出题人可操作；评测中的提交拒绝；使用题目当前数据重新入队。
     *
     * <p>三步缺一不可：reset DB（专用 SQL 置回 NULL）→ 清/刷 Redis 状态与 owner（成对）→ 重新压队列任务。
     */
    @Transactional
    public RejudgeResultVO rejudge(Long id) {
        Submission submission = submissionMapper.findById(id);
        if (submission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提交记录不存在");
        }
        checkRejudgePermission(submission);
        if (isJudging(submission.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该提交正在评测中，请稍后再试");
        }
        Question question = questionMapper.findById(submission.getQuestionId());
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "题目不存在或已删除");
        }
        List<TestCase> testCases = testCaseMapper.findByQuestionId(submission.getQuestionId());
        Map<String, String> code = parseCodeContent(submission.getCodeContent());

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
                .rightPush(QUEUE_KEY, writeJson(buildTask(submission, question, testCases, code)));
        return RejudgeResultVO.builder()
                .submissionId(id)
                .status(SubmissionStatus.PENDING.name())
                .build();
    }

    private void checkRejudgePermission(Submission submission) {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.hasRole(Role.ADMIN)) {
            return;
        }
        Question question = questionMapper.findById(submission.getQuestionId());
        if (question == null || !question.getCreatedBy().equals(user.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /** 评测中（非终态中的进行态）不允许重判。 */
    private boolean isJudging(String status) {
        return "PENDING".equals(status) || "COMPILING".equals(status) || "JUDGING".equals(status);
    }

    private Map<String, String> parseCodeContent(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /** 定时回写：将判题机写入 Redis 的终态结果落库 MySQL（幂等）。 */
    @Scheduled(fixedDelay = 2000)
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
                    log.warn("回写评测结果失败 submissionId={}: {}", submissionIdStr, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("回写任务扫描跳过: {}", e.getMessage());
        }
    }

    private void persistResult(Long submissionId) {
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
        Submission sub = submissionMapper.findById(submissionId);
        boolean newlyAccepted = sub != null
                && !"ACCEPTED".equals(sub.getStatus())
                && SubmissionStatus.ACCEPTED.name().equals(vo.getStatus());

        submissionMapper.updateResult(
                submissionId,
                vo.getStatus(),
                result == null ? null : result.getScore(),
                result == null ? null : result.getTimeUsedMs(),
                result == null ? null : result.getMemoryUsedKb(),
                result == null ? null : result.getCompileErrorLog(),
                result == null ? null : writeJson(result));

        if (newlyAccepted) {
            LocalDate date = sub.getCreatedAt() != null ? sub.getCreatedAt().toLocalDate() : LocalDate.now();
            userDailyAcService.recordAlgoAc(sub.getUserId(), date);
        }
    }

    private Map<String, Object> buildTask(
            Submission submission,
            Question question,
            List<TestCase> testCases,
            Map<String, String> code) {
        Map<String, Object> task = new HashMap<>();
        task.put("submissionId", submission.getId());
        task.put("questionId", submission.getQuestionId());
        task.put("language", submission.getLanguage());
        task.put("codeContent", code);
        task.put("timeLimitMs", question.getTimeLimit() == null ? 1000 : question.getTimeLimit());
        task.put(
                "memoryLimitMb",
                question.getMemoryLimit() == null ? 256 : question.getMemoryLimit());
        List<Map<String, Object>> cases =
                testCases.stream()
                        .map(
                                tc -> {
                                    Map<String, Object> c = new HashMap<>();
                                    c.put("caseId", tc.getId());
                                    c.put("caseNum", tc.getCaseNum() != null ? tc.getCaseNum() : 1);
                                    c.put("scoreWeight", tc.getScoreWeight() != null ? tc.getScoreWeight() : 10);
                                    if (tc.getInputPath() != null && !tc.getInputPath().isBlank()) {
                                        c.put("inputPath", tc.getInputPath());
                                        c.put("outputPath", tc.getOutputPath());
                                    } else {
                                        c.put("input", tc.getInput() != null ? tc.getInput() : "");
                                        c.put("expectedOutput", tc.getExpectedOutput() != null ? tc.getExpectedOutput() : "");
                                    }
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

    private SubmissionVO toVOFromEntity(Submission s) {
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
    private boolean canAccessSubmission(AuthUser user, Submission submission) {
        if (user.hasRole(Role.ADMIN)) {
            return true;
        }
        if (submission.getUserId().equals(user.getId())) {
            return true;
        }
        Question question = questionMapper.findById(submission.getQuestionId());
        return question != null && question.getCreatedBy().equals(user.getId());
    }

    private boolean supportsLanguage(Question question, String language) {
        if (question.getLanguages() == null || question.getLanguages().isBlank()) {
            return true;
        }
        return Arrays.stream(question.getLanguages().split(","))
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .anyMatch(l -> l.equalsIgnoreCase(language));
    }

    private Map<String, String> normalizeCode(Map<String, String> codeContent) {
        if (codeContent == null) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        codeContent.forEach(
                (k, v) -> {
                    if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                        result.put(k.trim(), v);
                    }
                });
        return result;
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
