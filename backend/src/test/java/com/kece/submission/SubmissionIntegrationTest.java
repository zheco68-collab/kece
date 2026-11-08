package com.kece.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import com.kece.practice.service.LessonPracticeService;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.entity.Submission;
import com.kece.submission.judging.CodeJudgeSettlement;
import com.kece.submission.mapper.SubmissionMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** 提交/轮询接口集成测试：验证持久任务状态、结算回写与无指针历史记录读取。 */
@SpringBootTest(properties = "kece.judging.persistent-ingress-enabled=true")
class SubmissionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SubmissionMapper submissionMapper;

    @SpyBean private StringRedisTemplate redis;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private CodeJudgeSettlement codeSettlement;
    @Autowired private LessonPracticeService practiceService;

    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private long createQuestionWithCase(String teacherToken) throws Exception {
        MvcResult q =
                mockMvc.perform(
                                post("/questions")
                                        .header("Authorization", "Bearer " + teacherToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "A+B_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                "description",
                                                                "计算两数之和",
                                                                "difficulty",
                                                                "EASY"))))
                        .andExpect(status().isOk())
                        .andReturn();
        long questionId = data(q).path("id").asLong();

        mockMvc.perform(
                        post("/questions/" + questionId + "/test-cases")
                                .header("Authorization", "Bearer " + teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "input",
                                                        "1 2",
                                                        "expectedOutput",
                                                        "3",
                                                        "isSample",
                                                        1))))
                .andExpect(status().isOk());
        return questionId;
    }

    @Test
    void submitThenPoll_shouldReflectPersistentStateAndHistoricalNullPointer() throws Exception {
        String teacherToken = loginToken("teacher", "teacher123");
        long questionId = createQuestionWithCase(teacherToken);

        String username = "stu_" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "username",
                                                        username,
                                                        "email",
                                                        username + "@example.com",
                                                        "password",
                                                        "pass123456"))))
                .andExpect(status().isOk());
        String userToken = loginToken(username, "pass123456");

        // 提交代码
        MvcResult submit =
                mockMvc.perform(
                                post("/submissions")
                                        .header("Authorization", "Bearer " + userToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "questionId",
                                                                questionId,
                                                                "language",
                                                                "cpp",
                                                                "codeContent",
                                                                Map.of(
                                                                        "main.cpp",
                                                                        "#include <iostream>\nint main(){return 0;}")))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(submit)).isEqualTo(200);
        long submissionId = data(submit).path("submissionId").asLong();
        assertThat(data(submit).path("status").asText()).isEqualTo("PENDING");
        assertThat(data(submit).path("waitReason").asText()).isEqualTo("WAITING_FOR_WORKER");

        // 轮询应读取持久任务状态，仍为 PENDING + WAITING_FOR_WORKER
        MvcResult poll =
                mockMvc.perform(
                                get("/submissions/" + submissionId)
                                        .header("Authorization", "Bearer " + userToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(data(poll).path("status").asText()).isEqualTo("PENDING");
        assertThat(data(poll).path("waitReason").asText()).isEqualTo("WAITING_FOR_WORKER");

        // 模拟持久任务结算写回终态结果
        SubmissionResultVO result =
                SubmissionResultVO.builder()
                        .score(100)
                        .timeUsedMs(12)
                        .memoryUsedKb(4096)
                        .testCasesReport(List.of())
                        .build();
        SubmissionVO terminal =
                SubmissionVO.builder()
                        .submissionId(submissionId)
                        .status("ACCEPTED")
                        .result(result)
                        .build();
        settleSubmission(submissionId, terminal);

        // 轮询应返回终态
        MvcResult done =
                mockMvc.perform(
                                get("/submissions/" + submissionId)
                                        .header("Authorization", "Bearer " + userToken))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode doneData = data(done);
        assertThat(doneData.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(doneData.path("result").path("score").asInt()).isEqualTo(100);

        Submission persisted = submissionMapper.findById(submissionId);
        assertThat(persisted.getStatus()).isEqualTo("ACCEPTED");
        assertThat(persisted.getResultJson()).isNotBlank();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT algo_count FROM user_daily_ac WHERE user_id = ? AND ac_date = ?",
                                Integer.class,
                                persisted.getUserId(),
                                persisted.getCreatedAt().toLocalDate()))
                .isEqualTo(1);

        // 重判应创建新的持久任务（generation = 2）并重置提交状态
        MvcResult rejudge =
                mockMvc.perform(
                                post("/admin/submissions/" + submissionId + "/rejudge")
                                        .header("Authorization", "Bearer " + teacherToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(rejudge)).isEqualTo(200);
        assertThat(data(rejudge).path("submissionId").asLong()).isEqualTo(submissionId);
        Submission reset = submissionMapper.findById(submissionId);
        assertThat(reset.getStatus()).isEqualTo("PENDING");
        assertThat(reset.getResultJson()).isNull();
        assertThat(reset.getCurrentJudgeJobId()).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT generation FROM judge_job WHERE id = ?",
                                Integer.class,
                                reset.getCurrentJudgeJobId()))
                .isEqualTo(2);

        // 重判结果为 WRONG_ANSWER 时移除贡献
        terminal.setStatus("WRONG_ANSWER");
        result.setScore(0);
        settleSubmission(submissionId, terminal);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT algo_count FROM user_daily_ac WHERE user_id = ? AND ac_date = ?",
                                Integer.class,
                                persisted.getUserId(),
                                persisted.getCreatedAt().toLocalDate()))
                .isZero();

        // 历史无指针（current_judge_job_id IS NULL）记录应可直接从数据库读取终态
        jdbc.update("UPDATE submission SET current_judge_job_id = NULL WHERE id = ?", submissionId);
        MvcResult historical =
                mockMvc.perform(
                                get("/submissions/" + submissionId)
                                        .header("Authorization", "Bearer " + userToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(data(historical).path("status").asText()).isEqualTo("WRONG_ANSWER");
        assertThat(data(historical).path("result").path("score").asInt()).isZero();
    }

    private void settleSubmission(long submissionId, SubmissionVO terminal) {
        transaction.executeWithoutResult(
                tx -> {
                    Submission sub = submissionMapper.findById(submissionId);
                    if (sub.getCurrentJudgeJobId() != null) {
                        jdbc.update(
                                """
                                UPDATE judge_job
                                SET state = 'COMPLETED', finished_cases = total_cases
                                WHERE id = ?
                                """,
                                sub.getCurrentJudgeJobId());
                    }
                    codeSettlement.applyPersistent(sub, terminal);
                });
    }

    @Test
    void durableSubmissionSurvivesRedisFailureAndRollsBackWithItsJob() throws Exception {
        String teacher = loginToken("teacher", "teacher123");
        long questionId = createQuestionWithCase(teacher);
        jdbc.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode, revision)
                VALUES ('python', 'PROBLEM', 'PERSISTENT', 7)
                """);
        try {
            doThrow(new RedisConnectionFailureException("isolated Redis outage"))
                    .when(redis)
                    .opsForValue();
            doThrow(new RedisConnectionFailureException("isolated Redis outage"))
                    .when(redis)
                    .opsForList();

            JsonNode accepted = submitPython(teacher, questionId);
            long id = accepted.path("submissionId").asLong();
            Long jobId = submissionMapper.findById(id).getCurrentJudgeJobId();
            assertThat(jobId).isNotNull();
            assertThat(
                            jdbc.queryForObject(
                                    """
                    SELECT COUNT(*) FROM judge_job
                    WHERE id = ? AND submission_type = 'ALGO' AND submission_id = ?
                      AND generation = 1 AND pool = 'python' AND route_revision = 7
                      AND state = 'QUEUED' AND JSON_VALID(request_snapshot)
                    """,
                                    Integer.class,
                                    jobId,
                                    id))
                    .isEqualTo(1);
            MvcResult polled =
                    mockMvc.perform(
                                    get("/submissions/" + id)
                                            .header("Authorization", "Bearer " + teacher))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(data(polled).path("status").asText()).isEqualTo("PENDING");
            assertThat(data(polled).path("waitReason").asText()).isEqualTo("WAITING_FOR_WORKER");

            Long rolledBack =
                    transaction.execute(
                            tx -> {
                                try {
                                    long pendingId =
                                            submitPython(teacher, questionId)
                                                    .path("submissionId")
                                                    .asLong();
                                    assertThat(
                                                    submissionMapper
                                                            .findById(pendingId)
                                                            .getCurrentJudgeJobId())
                                            .isNotNull();
                                    tx.setRollbackOnly();
                                    return pendingId;
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                }
                            });
            assertThat(submissionMapper.findById(rolledBack)).isNull();
            assertThat(
                            jdbc.queryForObject(
                                    """
                    SELECT COUNT(*) FROM judge_job WHERE submission_type = 'ALGO' AND submission_id = ?
                    """,
                                    Integer.class,
                                    rolledBack))
                    .isZero();

            jdbc.update("UPDATE judge_job SET state = 'COMPLETED' WHERE id = ?", jobId);
            jdbc.update("UPDATE submission SET status = 'ACCEPTED' WHERE id = ?", id);
            jdbc.update("DELETE FROM judge_pool_route WHERE pool = 'python' AND scene = 'PROBLEM'");
            MvcResult rejudge =
                    mockMvc.perform(
                                    post("/admin/submissions/" + id + "/rejudge")
                                            .header("Authorization", "Bearer " + teacher))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(code(rejudge)).isEqualTo(200);
            assertThat(
                            jdbc.queryForObject(
                                    """
                    SELECT j.generation
                    FROM submission s JOIN judge_job j ON j.id = s.current_judge_job_id
                    WHERE s.id = ? AND j.state = 'QUEUED'
                    """,
                                    Integer.class,
                                    id))
                    .isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM judge_pool_route WHERE pool = 'python' AND scene = 'PROBLEM'");
        }
    }

    @Test
    void oldJobDownloadsOnlyItsFrozenArtifactsWithAnActiveLease() throws Exception {
        String teacher = loginToken("teacher", "teacher123");
        long questionId = createQuestionWithCase(teacher);
        Long caseId =
                jdbc.queryForObject(
                        "SELECT id FROM test_case WHERE question_id = ?", Long.class, questionId);
        jdbc.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode)
                VALUES ('python', 'PROBLEM', 'PERSISTENT')
                """);
        try {
            long submission = submitPython(teacher, questionId).path("submissionId").asLong();
            long jobId = submissionMapper.findById(submission).getCurrentJudgeJobId();
            long frozen =
                    jdbc.queryForObject(
                            "SELECT manifest_id FROM judge_job WHERE id = ?", Long.class, jobId);
            String original = outputDigest(frozen);
            var edit =
                    mockMvc.perform(
                                    put("/questions/test-cases/" + caseId)
                                            .header("Authorization", "Bearer " + teacher)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"input\":\"1 2\",\"expectedOutput\":\"\",\"isSample\":1}"))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(code(edit)).isEqualTo(200);
            long current =
                    jdbc.queryForObject(
                            """
                    SELECT manifest_id FROM judge_question_manifest
                    WHERE submission_type = 'ALGO' AND question_id = ?
                    """,
                            Long.class,
                            questionId);
            assertThat(current).isNotEqualTo(frozen);
            String updated = outputDigest(current);
            assertThat(updated).isNotEqualTo(original);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT byte_size FROM judge_artifact WHERE digest = ?",
                                    Long.class,
                                    updated))
                    .isZero();

            String endpoint = "/internal/judgers/jobs/" + jobId + "/artifacts/";
            mockMvc.perform(get(endpoint + original)).andExpect(status().isUnauthorized());
            String token = "A".repeat(43);
            jdbc.update(
                    "INSERT INTO judge_host_group (name,max_slots) VALUES ('artifact-fixture',1)");
            jdbc.update(
                    """
                    INSERT INTO judge_node (host_group_id,name,pool,runtime_spec,max_slots,state,session_epoch,credential_hash)
                    SELECT id,'artifact-fixture','python','python-v1',1,'ENABLED',1,SHA2('artifact-fixture',256)
                    FROM judge_host_group WHERE name='artifact-fixture'
                    """);
            long node =
                    jdbc.queryForObject(
                            "SELECT id FROM judge_node WHERE name='artifact-fixture'", Long.class);
            jdbc.update(
                    """
                    UPDATE judge_job SET state = 'RUNNING', node_id = ?,
                        session_epoch = 1, lease_epoch = 1,
                        lease_until = UTC_TIMESTAMP(6) + INTERVAL 30 SECOND,
                        artifact_token_hash = SHA2(?, 256) WHERE id = ?
                    """,
                    node,
                    token,
                    jobId);
            var downloaded =
                    mockMvc.perform(
                                    get(endpoint + original)
                                            .header("Authorization", "Bearer " + token))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(downloaded.getResponse().getContentAsByteArray())
                    .containsExactly((byte) '3');
            assertThat(downloaded.getResponse().getHeader("X-Content-SHA256")).isEqualTo(original);
            mockMvc.perform(get(endpoint + updated).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
            mockMvc.perform(
                            get(endpoint + original)
                                    .header("Authorization", "Bearer " + "B".repeat(43)))
                    .andExpect(status().isNotFound());
            jdbc.update("UPDATE judge_job SET lease_until = UTC_TIMESTAMP(6) WHERE id = ?", jobId);
            mockMvc.perform(get(endpoint + original).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.update("DELETE FROM judge_pool_route WHERE pool = 'python' AND scene = 'PROBLEM'");
        }
    }

    private String outputDigest(long manifest) throws Exception {
        String body =
                jdbc.queryForObject(
                        "SELECT document FROM judge_manifest WHERE id = ?", String.class, manifest);
        return objectMapper
                .readTree(body)
                .path("cases")
                .get(0)
                .path("output")
                .path("digest")
                .asText();
    }

    @Test
    void manifestBackfillReportsMissingFileAndCanRetryFromTheSameCursor() throws Exception {
        String teacher = loginToken("teacher", "teacher123");
        String admin = loginToken("admin", "admin123");
        long questionId = createQuestionWithCase(teacher);
        String original =
                jdbc.queryForObject(
                        "SELECT input_path FROM test_case WHERE question_id = ?",
                        String.class,
                        questionId);
        jdbc.update(
                "DELETE FROM judge_question_manifest WHERE submission_type = 'ALGO' AND question_id = ?",
                questionId);
        jdbc.update(
                "UPDATE test_case SET input_path = ? WHERE question_id = ?",
                questionId + "/missing-test-fixture.in",
                questionId);
        String endpoint =
                "/admin/judging/manifests/backfill?type=ALGO&limit=1&afterId=" + (questionId - 1);
        var forbidden =
                mockMvc.perform(post(endpoint).header("Authorization", "Bearer " + teacher))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forbidden)).isEqualTo(4003);
        var failed =
                mockMvc.perform(post(endpoint).header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(data(failed).path("published").asInt()).isZero();
        assertThat(data(failed).path("failures").get(0).path("questionId").asLong())
                .isEqualTo(questionId);
        jdbc.update(
                "UPDATE test_case SET input_path = ? WHERE question_id = ?", original, questionId);
        var retried =
                mockMvc.perform(post(endpoint).header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(data(retried).path("published").asInt()).isEqualTo(1);
        assertThat(data(retried).path("failures")).isEmpty();
    }

    private JsonNode submitPython(String token, long questionId) throws Exception {
        return submitCode(
                token,
                Map.of(
                        "questionId",
                        questionId,
                        "language",
                        "python",
                        "codeContent",
                        Map.of("main.py", "print(3)")));
    }

    @Test
    void delayedPracticeResultUsesTheOriginalAttemptBinding() throws Exception {
        String teacher = loginToken("teacher", "teacher123");
        long questionId = createQuestionWithCase(teacher);
        Long teacherId =
                jdbc.queryForObject("SELECT id FROM user WHERE username = 'teacher'", Long.class);
        String room =
                UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        jdbc.update(
                "INSERT INTO lesson_practice (title, access_code, teacher_id) VALUES (?, ?, ?)",
                "out-of-order judging",
                room,
                teacherId);
        Long practiceId =
                jdbc.queryForObject(
                        "SELECT id FROM lesson_practice WHERE access_code = ?", Long.class, room);
        jdbc.update(
                "INSERT INTO lesson_practice_member (practice_id, user_id) VALUES (?, ?)",
                practiceId,
                teacherId);
        jdbc.update(
                """
                INSERT INTO lesson_practice_question (practice_id, question_id, question_type, title)
                VALUES (?, ?, 'ALGO', 'A+B')
                """,
                practiceId,
                questionId);
        Long practiceQuestionId =
                jdbc.queryForObject(
                        "SELECT id FROM lesson_practice_question WHERE practice_id = ?",
                        Long.class,
                        practiceId);
        var request =
                Map.of(
                        "questionId",
                        questionId,
                        "language",
                        "python",
                        "codeContent",
                        Map.of("main.py", "print(3)"),
                        "practiceCode",
                        room,
                        "practiceQuestionId",
                        practiceQuestionId);
        long first = submitCode(teacher, request).path("submissionId").asLong();
        long second = submitCode(teacher, request).path("submissionId").asLong();
        assertThat(first).isNotEqualTo(second);

        // The second attempt finishes first. The delayed first result still counts.
        completePracticeAttempt(second, "WRONG_ANSWER", 0);
        completePracticeAttempt(first, "ACCEPTED", 100);
        practiceService.onPracticalJudgeCompleted("ALGO", first);
        assertThat(
                        jdbc.queryForMap(
                                """
                SELECT submission_id, best_submission_id, attempt_count, score
                FROM lesson_practice_submission WHERE practice_id = ? AND user_id = ?
                """,
                                practiceId,
                                teacherId))
                .containsEntry("submission_id", second)
                .containsEntry("best_submission_id", first)
                .containsEntry("attempt_count", 2)
                .containsEntry("score", 10);
    }

    private void completePracticeAttempt(long id, String status, int score) {
        SubmissionVO terminal =
                SubmissionVO.builder()
                        .submissionId(id)
                        .status(status)
                        .result(SubmissionResultVO.builder().score(score).build())
                        .build();
        settleSubmission(id, terminal);
        practiceService.onPracticalJudgeCompleted("ALGO", id);
    }

    private JsonNode submitCode(String token, Map<String, ?> request) throws Exception {
        MvcResult submitted =
                mockMvc.perform(
                                post("/submissions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(submitted)).isEqualTo(200);
        return data(submitted);
    }
}
