package com.kece.exam.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.database.entity.DbQuestion;
import com.kece.database.entity.DbSubmission;
import com.kece.database.entity.DbTestCase;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.database.mapper.DbTestCaseMapper;
import com.kece.exam.dto.ExamAnswerRequest;
import com.kece.exam.dto.ExamSubmissionVO;
import com.kece.exam.dto.SingleSubmitVO;
import com.kece.exam.dto.SubmitResultVO;
import com.kece.exam.entity.Exam;
import com.kece.exam.entity.ExamQuestion;
import com.kece.exam.entity.ExamSessionStatus;
import com.kece.exam.entity.ExamSubmission;
import com.kece.exam.mapper.ExamMapper;
import com.kece.exam.mapper.ExamQuestionMapper;
import com.kece.exam.mapper.ExamSubmissionMapper;
import com.kece.objective.dto.AnswerResultVO;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import com.kece.objective.service.ObjectiveGrader;
import com.kece.question.entity.Question;
import com.kece.question.entity.TestCase;
import com.kece.question.mapper.QuestionMapper;
import com.kece.question.mapper.TestCaseMapper;
import com.kece.question.strategy.ContestSubmissionDTO;
import com.kece.question.strategy.QuestionStrategyFactory;
import com.kece.submission.entity.Submission;
import com.kece.submission.enums.SubmissionStatus;
import com.kece.submission.mapper.SubmissionMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 考试作答与交卷评测：客观题同步判分（快照分折算）；代码/数据库题批量入队复用现有评测链路。 */
@Service
@RequiredArgsConstructor
public class ExamSubmissionService {

    private static final String CODE_QUEUE_KEY = "judge:queue";
    private static final String SQL_QUEUE_KEY = "judge:sql:queue";
    private static final List<String> SUPPORTED_LANGUAGES =
            List.of("cpp", "java", "python", "arkts");

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final QuestionMapper questionMapper;
    private final TestCaseMapper testCaseMapper;
    private final DbQuestionMapper dbQuestionMapper;
    private final DbTestCaseMapper dbTestCaseMapper;
    private final ObjectiveQuestionMapper objectiveQuestionMapper;
    private final ObjectiveGrader grader;
    private final SubmissionMapper codeSubmissionMapper;
    private final DbSubmissionMapper dbSubmissionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final QuestionStrategyFactory strategyFactory;

    /** 保存作答（UPSERT by 考试+考生+快照条目）；已交卷的题目不可再改。 */
    @Transactional
    public void saveAnswer(Long examId, ExamAnswerRequest request) {
        AuthUser user = requireUser();
        Exam exam = getExamOrThrow(examId);
        ExamSessionStatus status = advanceStatus(exam);
        if (status == ExamSessionStatus.CANCELLED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该考试已取消");
        }
        if (LocalDateTime.now().isBefore(exam.getStartTime())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "考试尚未开始");
        }
        if (status == ExamSessionStatus.ENDED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "考试已结束，已自动交卷");
        }
        ExamQuestion examQuestion = examQuestionMapper.findById(request.getExamQuestionId());
        if (examQuestion == null || !examQuestion.getExamId().equals(examId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "题目快照不属于该考试");
        }
        if ("ALGO".equals(examQuestion.getQuestionType())) {
            String language =
                    request.getLanguage() == null ? "" : request.getLanguage().trim().toLowerCase();
            if (!SUPPORTED_LANGUAGES.contains(language)) {
                throw new BusinessException(
                        ResultCode.PARAM_ERROR, "不支持的语言: " + request.getLanguage());
            }
            parseCodeContent(request.getAnswer()); // 格式校验：必须是多文件 JSON
        }

        ExamSubmission existing =
                submissionMapper.findByUnique(examId, user.getId(), request.getExamQuestionId());
        if (existing != null && existing.getSubmitted() != null && existing.getSubmitted() == 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题已交卷，不能再修改");
        }
        if (existing == null) {
            ExamSubmission row = new ExamSubmission();
            row.setExamId(examId);
            row.setUserId(user.getId());
            row.setExamQuestionId(request.getExamQuestionId());
            row.setAnswerContent(request.getAnswer());
            row.setLanguage(
                    "ALGO".equals(examQuestion.getQuestionType())
                            ? request.getLanguage().trim().toLowerCase()
                            : null);
            row.setStatus(SubmissionStatus.PENDING.name());
            row.setSubmitted(0);
            submissionMapper.insert(row);
        } else {
            submissionMapper.updateAnswer(
                    existing.getId(),
                    request.getAnswer(),
                    "ALGO".equals(examQuestion.getQuestionType())
                            ? request.getLanguage().trim().toLowerCase()
                            : null,
                    SubmissionStatus.PENDING.name());
        }
    }

    /** 交卷评测（OI 统一评测）：客观题同步判分；代码/数据库题批量入队（ACM 已实时提交过的不重复入队）。可重复进入但不重复评测（幂等）。 */
    @Transactional
    public SubmitResultVO submit(Long examId) {
        AuthUser user = requireUser();
        Exam exam = getExamOrThrow(examId);
        ExamSessionStatus status = advanceStatus(exam);
        if (status == ExamSessionStatus.CANCELLED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该考试已取消");
        }
        if (status == ExamSessionStatus.UPCOMING
                && LocalDateTime.now().isBefore(exam.getStartTime())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "考试尚未开始");
        }
        List<ExamSubmission> rows = submissionMapper.findByExamAndUser(examId, user.getId());
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "尚未作答任何题目");
        }
        if (rows.stream().allMatch(r -> r.getSubmitted() != null && r.getSubmitted() == 1)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该考试已交卷，不能重复提交");
        }
        boolean acm = "ACM".equals(exam.getMode());
        int graded = 0;
        int queued = 0;
        for (ExamSubmission row : rows) {
            if (row.getSubmitted() != null && row.getSubmitted() == 1) {
                continue;
            }
            ExamQuestion examQuestion = examQuestionMapper.findById(row.getExamQuestionId());
            if (examQuestion == null) {
                continue;
            }
            switch (examQuestion.getQuestionType()) {
                case "OBJECTIVE" -> {
                    gradeObjective(row, examQuestion);
                    graded++;
                }
                case "ALGO", "DB", "DATABASE" -> {
                    // ACM：考试中已实时提交过的题目不重复入队，成绩取最后一次提交
                    if (!acm || !hasContestSubmission(exam, user, examQuestion)) {
                        if ("ALGO".equals(examQuestion.getQuestionType())) {
                            enqueueCode(row, examQuestion);
                        } else {
                            enqueueDb(row, examQuestion);
                        }
                        queued++;
                    }
                }
                default -> {}
            }
            submissionMapper.markSubmitted(row.getId());
        }
        return SubmitResultVO.builder().examId(examId).graded(graded).queued(queued).build();
    }

    /** ACM 赛制考试中单题实时提交：立即入队并返回提交记录 ID，前端轮询判题状态。 OI 赛制与客观题拒绝（客观题交卷后统一判分）。 */
    @Transactional
    public SingleSubmitVO submitQuestion(Long examId, Long examQuestionId) {
        AuthUser user = requireUser();
        Exam exam = getExamOrThrow(examId);
        ExamSessionStatus status = advanceStatus(exam);
        if (status == ExamSessionStatus.CANCELLED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该考试已取消");
        }
        if (status == ExamSessionStatus.ENDED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "考试已结束，已自动交卷");
        }
        if (status == ExamSessionStatus.UPCOMING
                && LocalDateTime.now().isBefore(exam.getStartTime())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "考试尚未开始");
        }
        if (!"ACM".equals(exam.getMode())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "OI 赛制不支持考试中单题提交");
        }
        ExamQuestion examQuestion = examQuestionMapper.findById(examQuestionId);
        if (examQuestion == null || !examQuestion.getExamId().equals(examId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "题目快照不属于该考试");
        }
        ExamSubmission row = submissionMapper.findByUnique(examId, user.getId(), examQuestionId);
        if (row == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先保存本题作答再提交");
        }
        if (row.getSubmitted() != null && row.getSubmitted() == 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该题已交卷，不能重复提交");
        }
        Long submissionId;
        switch (examQuestion.getQuestionType()) {
            case "ALGO" -> submissionId = enqueueCode(row, examQuestion);
            case "DB", "DATABASE" -> submissionId = enqueueDb(row, examQuestion);
            case "OBJECTIVE" ->
                    throw new BusinessException(ResultCode.PARAM_ERROR, "客观题在交卷后统一判分，考试中不支持单题提交");
            default ->
                    throw new BusinessException(
                            ResultCode.PARAM_ERROR, "不支持的题型: " + examQuestion.getQuestionType());
        }
        if (submissionId == null) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "题目数据缺失，提交失败");
        }
        return SingleSubmitVO.builder()
                .submissionId(submissionId)
                .questionType(examQuestion.getQuestionType())
                .status(SubmissionStatus.PENDING.name())
                .build();
    }

    /** 该考试该考生该题是否已有实时提交（ACM 交卷时避免重复入队）。 */
    private boolean hasContestSubmission(Exam exam, AuthUser user, ExamQuestion eq) {
        return strategyFactory
                .findStrategy(eq.getQuestionType())
                .flatMap(
                        s ->
                                s.findLatestContestSubmission(
                                        exam.getId(), user.getId(), eq.getQuestionId()))
                .isPresent();
    }

    /** 我的作答与成绩：以考卷全部题目为基准返回（即使未作答也列出），代码/数据库题沙箱分数按满分折算。 */
    public List<ExamSubmissionVO> myResults(Long examId) {
        AuthUser user = requireUser();
        Exam exam = getExamOrThrow(examId);
        ExamSessionStatus status = advanceStatus(exam);
        if (status == ExamSessionStatus.CANCELLED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该考试已取消");
        }
        boolean ended = status == ExamSessionStatus.ENDED;
        boolean acm = "ACM".equals(exam.getMode());

        List<ExamQuestion> examQuestions = examQuestionMapper.findByExamId(examId);
        List<ExamSubmission> userSubmissions =
                submissionMapper.findByExamAndUser(examId, user.getId());
        Map<Long, ExamSubmission> subByQuestionId =
                userSubmissions.stream()
                        .collect(
                                Collectors.toMap(
                                        ExamSubmission::getExamQuestionId, s -> s, (a, b) -> a));

        boolean userSubmitted =
                ended
                        || userSubmissions.stream()
                                .anyMatch(r -> r.getSubmitted() != null && r.getSubmitted() == 1);

        return examQuestions.stream()
                .map(
                        eq -> {
                            ExamSubmission row = subByQuestionId.get(eq.getId());
                            String type = eq.getQuestionType();
                            String rowStatus = row == null ? null : row.getStatus();
                            Integer score = row == null ? null : row.getScore();
                            String answer = row == null ? null : row.getAnswerContent();
                            String language = row == null ? null : row.getLanguage();

                            ContestSubmissionDTO latest =
                                    strategyFactory
                                            .findStrategy(type)
                                            .flatMap(
                                                    s ->
                                                            s.findLatestContestSubmission(
                                                                    examId,
                                                                    user.getId(),
                                                                    eq.getQuestionId()))
                                            .orElse(null);
                            if (latest != null) {
                                rowStatus = latest.status();
                                score = scaleScore(latest.score(), eq.getScore(), rowStatus);
                            } else if (score != null && !"OBJECTIVE".equals(type)) {
                                score = scaleScore(score, eq.getScore(), rowStatus);
                            }

                            // 未作答试题在交卷后明确给 0 分，状态设为 UNANSWERED
                            if (row == null && userSubmitted) {
                                score = 0;
                                rowStatus = "UNANSWERED";
                            }

                            // 未结束且未交卷时：客观题与 OI 赛制不提前公布
                            if (!ended && !userSubmitted && ("OBJECTIVE".equals(type) || !acm)) {
                                rowStatus = null;
                                score = null;
                            }
                            return ExamSubmissionVO.builder()
                                    .examQuestionId(eq.getId())
                                    .questionId(eq.getQuestionId())
                                    .questionType(type)
                                    .title(eq.getTitle())
                                    .score(score)
                                    .fullScore(eq.getScore())
                                    .answer(answer)
                                    .language(language)
                                    .status(rowStatus)
                                    .build();
                        })
                .toList();
    }

    private Integer scaleScore(Integer judgeScore, Integer fullScore, String status) {
        if ("ACCEPTED".equals(status)) {
            return fullScore != null ? fullScore : 0;
        }
        if (judgeScore == null || fullScore == null || judgeScore <= 0 || fullScore <= 0) {
            return 0;
        }
        int earned = (int) Math.round((double) judgeScore * fullScore / 100.0);
        if (earned == 0 && fullScore > 0 && judgeScore > 0) {
            earned = 1;
        }
        return Math.min(earned, fullScore);
    }

    /** 客观题同步判分：对错用题目当前答案，得分按快照分值折算（多选漏选半分向上取整）。 */
    private void gradeObjective(ExamSubmission row, ExamQuestion examQuestion) {
        ObjectiveQuestion question = objectiveQuestionMapper.findById(examQuestion.getQuestionId());
        if (question == null) {
            submissionMapper.updateResult(row.getId(), "SYSTEM_ERROR", 0, null);
            return;
        }
        AnswerResultVO result = grader.grade(question, row.getAnswerContent());
        int snapshotScore = examQuestion.getScore() == null ? 0 : examQuestion.getScore();
        int score;
        if (result.isCorrect()) {
            score = snapshotScore;
        } else if (result.getScore() > 0) {
            // 多选漏选：所选全对但未选全，得快照分一半（向上取整）
            score = (snapshotScore + 1) / 2;
        } else {
            score = 0;
        }
        String status = result.isCorrect() ? "ACCEPTED" : "WRONG_ANSWER";
        Map<String, Object> resultJson = new HashMap<>();
        resultJson.put("correct", result.isCorrect());
        resultJson.put("fullScore", snapshotScore);
        try {
            submissionMapper.updateResult(
                    row.getId(), status, score, objectMapper.writeValueAsString(resultJson));
        } catch (Exception e) {
            submissionMapper.updateResult(row.getId(), status, score, null);
        }
    }

    /** 代码题：写入 submission 行（contest_id=考试）并压 judge:queue；成绩由判题机回写该行。返回提交记录 ID。 */
    private Long enqueueCode(ExamSubmission row, ExamQuestion examQuestion) {
        Question question = questionMapper.findById(examQuestion.getQuestionId());
        if (question == null) {
            submissionMapper.updateJudgeResult(row.getId(), "SYSTEM_ERROR", 0, null);
            return null;
        }
        String language =
                row.getLanguage() == null ? "cpp" : row.getLanguage().trim().toLowerCase();
        Map<String, String> code = parseCodeContent(row.getAnswerContent());

        Submission submission = new Submission();
        submission.setUserId(row.getUserId());
        submission.setQuestionId(examQuestion.getQuestionId());
        submission.setContestId(row.getExamId());
        submission.setLanguage(language);
        submission.setCodeContent(row.getAnswerContent());
        submission.setStatus(SubmissionStatus.PENDING.name());
        codeSubmissionMapper.insert(submission);

        List<TestCase> testCases = testCaseMapper.findByQuestionId(examQuestion.getQuestionId());
        Map<String, Object> task = new HashMap<>();
        task.put("submissionId", submission.getId());
        task.put("questionId", examQuestion.getQuestionId());
        task.put("language", language);
        task.put("codeContent", code);
        task.put("timeLimitMs", question.getTimeLimit() == null ? 1000 : question.getTimeLimit());
        task.put(
                "memoryLimitMb",
                question.getMemoryLimit() == null ? 256 : question.getMemoryLimit());
        task.put(
                "testCases",
                testCases.stream()
                        .map(
                                tc -> {
                                    Map<String, Object> c = new HashMap<>();
                                    c.put("caseId", tc.getId());
                                    c.put("caseNum", tc.getCaseNum() != null ? tc.getCaseNum() : 1);
                                    c.put(
                                            "scoreWeight",
                                            tc.getScoreWeight() != null ? tc.getScoreWeight() : 10);
                                    if (tc.getInputPath() != null && !tc.getInputPath().isBlank()) {
                                        c.put("inputPath", tc.getInputPath());
                                        c.put("outputPath", tc.getOutputPath());
                                    } else {
                                        c.put("input", tc.getInput() != null ? tc.getInput() : "");
                                        c.put(
                                                "expectedOutput",
                                                tc.getExpectedOutput() != null
                                                        ? tc.getExpectedOutput()
                                                        : "");
                                    }
                                    return c;
                                })
                        .toList());
        redis.opsForList().rightPush(CODE_QUEUE_KEY, writeJson(task));
        submissionMapper.updateJudgeResult(
                row.getId(),
                SubmissionStatus.PENDING.name(),
                null,
                writeJson(Map.of("submissionId", submission.getId())));
        return submission.getId();
    }

    /** 数据库题：写入 db_submission 行（contest_id=考试）并压 judge:sql:queue。返回提交记录 ID。 */
    private Long enqueueDb(ExamSubmission row, ExamQuestion examQuestion) {
        DbQuestion question = dbQuestionMapper.findById(examQuestion.getQuestionId());
        if (question == null) {
            submissionMapper.updateJudgeResult(row.getId(), "SYSTEM_ERROR", 0, null);
            return null;
        }
        DbSubmission submission = new DbSubmission();
        submission.setUserId(row.getUserId());
        submission.setQuestionId(examQuestion.getQuestionId());
        submission.setContestId(row.getExamId());
        submission.setDialect(question.getDialect());
        submission.setSqlContent(row.getAnswerContent());
        submission.setStatus(SubmissionStatus.PENDING.name());
        dbSubmissionMapper.insert(submission);

        List<DbTestCase> testCases =
                dbTestCaseMapper.findByQuestionId(examQuestion.getQuestionId());
        Map<String, Object> task = new HashMap<>();
        task.put("submissionId", submission.getId());
        task.put("questionId", examQuestion.getQuestionId());
        task.put("type", "sql");
        task.put("dialect", question.getDialect());
        task.put("schemaSql", question.getSchemaSql());
        task.put("referenceSql", question.getReferenceSql());
        task.put("verifySql", question.getVerifySql());
        task.put(
                "orderSensitive",
                question.getOrderSensitive() != null && question.getOrderSensitive() == 1);
        task.put("codeContent", Map.of("answer.sql", row.getAnswerContent()));
        task.put("timeLimitMs", 5000);
        task.put(
                "testCases",
                testCases.stream()
                        .map(
                                tc -> {
                                    Map<String, Object> c = new HashMap<>();
                                    c.put("caseId", tc.getId());
                                    c.put("seedSql", tc.getSeedSql());
                                    return c;
                                })
                        .toList());
        redis.opsForList().rightPush(SQL_QUEUE_KEY, writeJson(task));
        submissionMapper.updateJudgeResult(
                row.getId(),
                SubmissionStatus.PENDING.name(),
                null,
                writeJson(Map.of("submissionId", submission.getId())));
        return submission.getId();
    }

    /** 状态机推进：UPCOMING→ONGOING→ENDED，变化持久化到 exam.session_status。 */
    private ExamSessionStatus advanceStatus(Exam exam) {
        LocalDateTime now = LocalDateTime.now();
        ExamSessionStatus current =
                exam.getSessionStatus() == null
                        ? ExamSessionStatus.UPCOMING
                        : ExamSessionStatus.valueOf(exam.getSessionStatus());
        ExamSessionStatus target;
        if (current == ExamSessionStatus.CANCELLED) {
            return current;
        }
        if (exam.getEndTime() != null && !now.isBefore(exam.getEndTime())) {
            target = ExamSessionStatus.ENDED;
        } else if (exam.getStartTime() != null && !now.isBefore(exam.getStartTime())) {
            target = ExamSessionStatus.ONGOING;
        } else {
            target = ExamSessionStatus.UPCOMING;
        }
        if (current != target) {
            exam.setSessionStatus(target.name());
            examMapper.updateSessionStatus(exam.getId(), target.name());
        }
        return target;
    }

    private Map<String, String> parseCodeContent(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "代码内容格式不正确（需多文件 JSON）");
        }
    }

    private AuthUser requireUser() {
        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    private Exam getExamOrThrow(Long id) {
        Exam exam = examMapper.findById(id);
        if (exam == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "考试不存在");
        }
        return exam;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "JSON 序列化失败");
        }
    }
}
