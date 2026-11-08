package com.kece.judging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.AbstractIntegrationTest;
import com.kece.database.dto.DbQuestionRequest;
import com.kece.database.dto.DbTestCaseRequest;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.judging.control.JudgeScheduler;
import com.kece.judging.control.JudgeSettlementEvents;
import com.kece.submission.mapper.SubmissionMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "kece.judging.persistent-ingress-enabled=true")
class AllPoolsBusinessIntegrationTest extends AbstractIntegrationTest {

    private static final String INTERNAL = "/internal/judgers";

    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate db;
    @Autowired private JudgeScheduler scheduler;
    @Autowired private JudgeSettlementEvents settlementEvents;
    @Autowired private SubmissionMapper submissionMapper;
    @Autowired private DbSubmissionMapper dbSubmissionMapper;

    private record Node(String id, String token, long epoch, String pool) {}

    private String adminToken;
    private String teacherToken;
    private Long teacherUserId;

    @BeforeEach
    void setUp() throws Exception {
        db.update("UPDATE judge_job SET state = 'CANCELLED' WHERE state IN ('QUEUED', 'RUNNING')");
        adminToken = loginToken("admin", "admin123");
        teacherToken = loginToken("teacher", "teacher123");
        teacherUserId =
                db.queryForObject("SELECT id FROM user WHERE username='teacher'", Long.class);
    }

    @AfterEach
    void tearDown() {
        db.update(
                "DELETE FROM judge_pool_route WHERE pool IN ('cpp','java','python','arkts','mysql')");
    }

    @Test
    void allFivePoolsDispatchAndSettleCorrectly() throws Exception {
        db.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode)
                VALUES ('cpp','PROBLEM','PERSISTENT'),
                       ('java','PROBLEM','PERSISTENT'),
                       ('python','PROBLEM','PERSISTENT'),
                       ('arkts','PROBLEM','PERSISTENT'),
                       ('mysql','PROBLEM','PERSISTENT')
                """);

        String hostId = host(adminToken, 10);
        Node nodeCpp = ready(adminToken, hostId, "cpp");
        Node nodeJava = ready(adminToken, hostId, "java");
        Node nodePy = ready(adminToken, hostId, "python");
        Node nodeArkts = ready(adminToken, hostId, "arkts");
        Node nodeSql = ready(adminToken, hostId, "mysql");

        // 1. C++, Java, Python, ArkTS 代码提交闭环
        Map<String, Node> codeNodes =
                Map.of(
                        "cpp", nodeCpp,
                        "java", nodeJava,
                        "python", nodePy,
                        "arkts", nodeArkts);

        for (Map.Entry<String, Node> entry : codeNodes.entrySet()) {
            String pool = entry.getKey();
            Node node = entry.getValue();

            long questionId = createAlgoQuestion(teacherToken, pool);
            long subId = submitAlgo(teacherToken, questionId, pool);

            JsonNode jobs = claim(node);
            assertThat(jobs.size()).isEqualTo(1);
            JsonNode job = jobs.get(0);
            assertThat(job.path("pool").asText()).isEqualTo(pool);
            assertThat(job.path("runtimeSpec").asText()).isEqualTo(pool + "-v1");
            assertThat(job.path("submissionType").asText()).isEqualTo("ALGO");

            String jobId = job.path("jobId").asText();
            var verdict = accepted(job);
            var committed = machine(node, "/jobs/" + jobId + "/result", verdict, 200);
            assertThat(committed.path("status").asText()).isEqualTo("COMMITTED");

            assertThat(
                            db.queryForObject(
                                    "SELECT status FROM submission WHERE id=?",
                                    String.class,
                                    subId))
                    .isEqualTo("ACCEPTED");
            assertThat(
                            db.queryForObject(
                                    "SELECT score FROM submission WHERE id=?",
                                    Integer.class,
                                    subId))
                    .isEqualTo(100);

            // 结算事件验证
            Long eventId =
                    db.queryForObject(
                            "SELECT id FROM judge_settlement_event WHERE job_id=? AND state='PENDING'",
                            Long.class,
                            Long.parseLong(jobId));
            assertThat(eventId).isNotNull();
            settlementEvents.apply(eventId);
            assertThat(
                            db.queryForObject(
                                    "SELECT state FROM judge_settlement_event WHERE id=?",
                                    String.class,
                                    eventId))
                    .isEqualTo("DONE");
        }

        // 2. MySQL 数据库评测闭环
        long dbQId = createDbQuestion(teacherToken);
        long sqlSubId =
                submitSql(teacherToken, dbQId, "SELECT id, name FROM emp WHERE dept = 'RD'");

        JsonNode sqlJobs = claim(nodeSql);
        assertThat(sqlJobs.size()).isEqualTo(1);
        JsonNode sqlJob = sqlJobs.get(0);
        assertThat(sqlJob.path("pool").asText()).isEqualTo("mysql");
        assertThat(sqlJob.path("runtimeSpec").asText()).isEqualTo("mysql-v1");
        assertThat(sqlJob.path("submissionType").asText()).isEqualTo("DATABASE");

        String sqlJobId = sqlJob.path("jobId").asText();
        var sqlVerdict = accepted(sqlJob);
        var sqlCommitted = machine(nodeSql, "/jobs/" + sqlJobId + "/result", sqlVerdict, 200);
        assertThat(sqlCommitted.path("status").asText()).isEqualTo("COMMITTED");

        assertThat(
                        db.queryForObject(
                                "SELECT status FROM db_submission WHERE id=?",
                                String.class,
                                sqlSubId))
                .isEqualTo("ACCEPTED");
        assertThat(
                        db.queryForObject(
                                "SELECT score FROM db_submission WHERE id=?",
                                Integer.class,
                                sqlSubId))
                .isEqualTo(100);

        Long sqlEventId =
                db.queryForObject(
                        "SELECT id FROM judge_settlement_event WHERE job_id=? AND state='PENDING'",
                        Long.class,
                        Long.parseLong(sqlJobId));
        assertThat(sqlEventId).isNotNull();
        settlementEvents.apply(sqlEventId);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM judge_settlement_event WHERE id=?",
                                String.class,
                                sqlEventId))
                .isEqualTo("DONE");
    }

    @Test
    void practiceSceneDispatchAndSettlement() throws Exception {
        db.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode)
                VALUES ('python','PRACTICE','PERSISTENT'),
                       ('mysql','PRACTICE','PERSISTENT')
                """);

        String hostId = host(adminToken, 4);
        Node nodePy = ready(adminToken, hostId, "python");
        Node nodeSql = ready(adminToken, hostId, "mysql");

        String room =
                UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        db.update(
                "INSERT INTO lesson_practice (title, access_code, teacher_id) VALUES (?, ?, ?)",
                "Practice Test",
                room,
                teacherUserId);
        Long practiceId =
                db.queryForObject(
                        "SELECT id FROM lesson_practice WHERE access_code = ?", Long.class, room);
        db.update(
                "INSERT INTO lesson_practice_member (practice_id, user_id) VALUES (?, ?)",
                practiceId,
                teacherUserId);

        long pyQId = createAlgoQuestion(teacherToken, "python");
        long sqlQId = createDbQuestion(teacherToken);

        db.update(
                """
                INSERT INTO lesson_practice_question (practice_id, question_id, question_type, title, score)
                VALUES (?, ?, 'ALGO', 'Python Practice Question', 100),
                       (?, ?, 'DB', 'SQL Practice Question', 100)
                """,
                practiceId,
                pyQId,
                practiceId,
                sqlQId);

        Long pyPqId =
                db.queryForObject(
                        "SELECT id FROM lesson_practice_question WHERE practice_id = ? AND question_type = 'ALGO'",
                        Long.class,
                        practiceId);
        Long sqlPqId =
                db.queryForObject(
                        "SELECT id FROM lesson_practice_question WHERE practice_id = ? AND question_type = 'DB'",
                        Long.class,
                        practiceId);

        // 提交课练代码题
        var pyReq =
                Map.of(
                        "questionId", pyQId,
                        "language", "python",
                        "codeContent", Map.of("main.py", "print(5)"),
                        "practiceCode", room,
                        "practiceQuestionId", pyPqId);
        long pySubId = submitAlgoWithPayload(teacherToken, pyReq);

        // 提交课练 SQL 题
        var sqlReq =
                Map.of(
                        "sql", "SELECT id, name FROM emp WHERE dept = 'RD'",
                        "practiceCode", room,
                        "practiceQuestionId", sqlPqId);
        long sqlSubId = submitSqlWithPayload(teacherToken, sqlQId, sqlReq);

        // Python 节点领单并完成
        JsonNode pyJobs = claim(nodePy);
        assertThat(pyJobs.size()).isEqualTo(1);
        JsonNode pyJob = pyJobs.get(0);
        long pyJobId = pyJob.path("jobId").asLong();
        assertThat(
                        db.queryForObject(
                                "SELECT scene FROM judge_job WHERE id=?", String.class, pyJobId))
                .isEqualTo("PRACTICE");
        machine(nodePy, "/jobs/" + pyJobId + "/result", accepted(pyJob), 200);

        // MySQL 节点领单并完成
        JsonNode sqlJobs = claim(nodeSql);
        assertThat(sqlJobs.size()).isEqualTo(1);
        JsonNode sqlJob = sqlJobs.get(0);
        long sqlJobId = sqlJob.path("jobId").asLong();
        assertThat(
                        db.queryForObject(
                                "SELECT scene FROM judge_job WHERE id=?", String.class, sqlJobId))
                .isEqualTo("PRACTICE");
        machine(nodeSql, "/jobs/" + sqlJobId + "/result", accepted(sqlJob), 200);

        // 触发结算事件
        List<Long> events = settlementEvents.due();
        for (Long eventId : events) {
            settlementEvents.apply(eventId);
        }

        // 验证课练成绩已自动沉淀到 lesson_practice_submission
        var pracSubPy =
                db.queryForMap(
                        "SELECT submission_id, score, status FROM lesson_practice_submission WHERE practice_id = ? AND user_id = ? AND practice_question_id = ?",
                        practiceId,
                        teacherUserId,
                        pyPqId);
        assertThat(pracSubPy.get("submission_id")).isEqualTo(pySubId);
        assertThat(pracSubPy.get("status")).isEqualTo("ACCEPTED");
        assertThat(pracSubPy.get("score")).isEqualTo(100);

        var pracSubSql =
                db.queryForMap(
                        "SELECT submission_id, score, status FROM lesson_practice_submission WHERE practice_id = ? AND user_id = ? AND practice_question_id = ?",
                        practiceId,
                        teacherUserId,
                        sqlPqId);
        assertThat(pracSubSql.get("submission_id")).isEqualTo(sqlSubId);
        assertThat(pracSubSql.get("status")).isEqualTo("ACCEPTED");
        assertThat(pracSubSql.get("score")).isEqualTo(100);
    }

    @Test
    void examSceneDispatchAndHandInIdempotency() throws Exception {
        db.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode)
                VALUES ('cpp','EXAM','PERSISTENT')
                """);

        String hostId = host(adminToken, 2);
        Node nodeCpp = ready(adminToken, hostId, "cpp");

        String student = registerStudent();
        long examId = createExamWithObjectiveAndCode(teacherToken);

        long objEqId = examQuestionId(student, examId, "OBJECTIVE");
        long codeEqId = examQuestionId(student, examId, "ALGO");

        saveAnswer(student, examId, objEqId, Map.of("examQuestionId", objEqId, "answer", "B"));
        saveAnswer(
                student,
                examId,
                codeEqId,
                Map.of(
                        "examQuestionId",
                        codeEqId,
                        "answer",
                        json.writeValueAsString(Map.of("main.cpp", "int main(){return 0;}")),
                        "language",
                        "cpp"));

        // 交卷
        MvcResult handIn =
                mockMvc.perform(
                                post("/exams/" + examId + "/submit")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(handIn)).isEqualTo(200);
        assertThat(data(handIn).path("queued").asInt()).isEqualTo(1);

        // 重复交卷幂等拒绝
        MvcResult duplicate =
                mockMvc.perform(
                                post("/exams/" + examId + "/submit")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(duplicate)).isEqualTo(4001);

        // 评测机领单
        JsonNode jobs = claim(nodeCpp);
        assertThat(jobs.size()).isEqualTo(1);
        JsonNode job = jobs.get(0);
        long jobId = job.path("jobId").asLong();
        assertThat(db.queryForObject("SELECT scene FROM judge_job WHERE id=?", String.class, jobId))
                .isEqualTo("EXAM");
        assertThat(job.path("pool").asText()).isEqualTo("cpp");

        // 回传判分结果
        machine(nodeCpp, "/jobs/" + jobId + "/result", accepted(job), 200);

        // 查验成绩
        MvcResult results =
                mockMvc.perform(
                                get("/exams/" + examId + "/results")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode list = data(results);
        assertThat(list.size()).isEqualTo(2);
        for (JsonNode row : list) {
            assertThat(row.path("status").asText()).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void rejudgeIncrementsGenerationAndStaleLeaseProtection() throws Exception {
        db.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode)
                VALUES ('python','PROBLEM','PERSISTENT'),('python','REJUDGE','PERSISTENT')
                """);

        String hostId = host(adminToken, 2);
        Node nodePy = ready(adminToken, hostId, "python");

        long qId = createAlgoQuestion(teacherToken, "python");
        long subId = submitAlgo(teacherToken, qId, "python");

        // 第一次判题（Gen 1）
        JsonNode firstJobs = claim(nodePy);
        JsonNode firstJob = firstJobs.get(0);
        long firstJobId = firstJob.path("jobId").asLong();
        assertThat(firstJob.path("generation").asInt()).isEqualTo(1);
        machine(nodePy, "/jobs/" + firstJobId + "/result", accepted(firstJob), 200);

        assertThat(
                        db.queryForObject(
                                "SELECT status FROM submission WHERE id=?", String.class, subId))
                .isEqualTo("ACCEPTED");

        // 管理员触发重判
        MvcResult rejudge =
                mockMvc.perform(
                                post("/admin/submissions/" + subId + "/rejudge")
                                        .header("Authorization", "Bearer " + teacherToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(rejudge)).isEqualTo(200);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM submission WHERE id=?", String.class, subId))
                .isEqualTo("PENDING");

        // 第二次领单（Gen 2）
        JsonNode secondJobs = claim(nodePy);
        assertThat(secondJobs.size()).isEqualTo(1);
        JsonNode secondJob = secondJobs.get(0);
        long secondJobId = secondJob.path("jobId").asLong();
        assertThat(secondJobId).isNotEqualTo(firstJobId);
        assertThat(secondJob.path("generation").asInt()).isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT scene FROM judge_job WHERE id=?",
                                String.class,
                                secondJobId))
                .isEqualTo("REJUDGE");

        // 迟到/旧世代结果提交被 409 拦截（STALE_LEASE / RESULT_CONFLICT）
        machine(nodePy, "/jobs/" + firstJobId + "/result", accepted(firstJob), 409);

        // 新世代正常提交
        machine(nodePy, "/jobs/" + secondJobId + "/result", accepted(secondJob), 200);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM submission WHERE id=?", String.class, subId))
                .isEqualTo("ACCEPTED");
    }

    @Test
    void deletedSubmissionDoesNotReviveAndReapCancelsJob() throws Exception {
        db.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode)
                VALUES ('python','PROBLEM','PERSISTENT')
                """);

        String hostId = host(adminToken, 2);
        Node nodePy = ready(adminToken, hostId, "python");

        long qId = createAlgoQuestion(teacherToken, "python");
        long subId = submitAlgo(teacherToken, qId, "python");

        JsonNode jobs = claim(nodePy);
        JsonNode job = jobs.get(0);
        long jobId = job.path("jobId").asLong();

        // 模拟业务提交被删除
        db.update("DELETE FROM submission WHERE id = ?", subId);

        // 评测机迟到的回传不能复活已删除的提交，直接 409
        machine(nodePy, "/jobs/" + jobId + "/result", accepted(job), 409);

        // 提交表依然为空（未复活）
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM submission WHERE id=?", Integer.class, subId))
                .isZero();

        // 过期探测或 reap 清理该 job，状态变为 CANCELLED
        db.update("UPDATE judge_job SET lease_until=UTC_TIMESTAMP(6) WHERE id=?", jobId);
        scheduler.reap(jobId);

        assertThat(db.queryForObject("SELECT state FROM judge_job WHERE id=?", String.class, jobId))
                .isEqualTo("CANCELLED");
    }

    // ---------- Helper methods ----------

    private String host(String admin, int slots) throws Exception {
        return adminPost(
                        admin,
                        "/admin/judge-host-groups",
                        Map.of("name", random(), "maxSlots", slots))
                .path("id")
                .asText();
    }

    private Node ready(String admin, String host, String pool) throws Exception {
        JsonNode enrollment =
                adminPost(
                        admin,
                        "/admin/judgers",
                        Map.of("name", random(), "pool", pool, "hostGroupId", host, "maxSlots", 2));
        var join =
                Map.of(
                        "enrollmentCode",
                        enrollment.path("enrollmentCode").asText(),
                        "protocolVersion",
                        1,
                        "bootId",
                        random());
        var joined =
                mockMvc.perform(
                                post(INTERNAL + "/join")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(join)))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode data = json.readTree(joined.getResponse().getContentAsByteArray());
        Node n =
                new Node(
                        data.path("nodeId").asText(),
                        data.path("credential").asText(),
                        data.path("sessionEpoch").asLong(),
                        pool);
        var healthy =
                Map.of(
                        "runtimeSpec",
                        pool + "-v1",
                        "runtimeVersion",
                        "fixture-1",
                        "localSlots",
                        2,
                        "healthy",
                        true);
        machine(n, "/heartbeat", healthy, 200);
        String path = "/admin/judgers/" + n.id();
        String check = adminPost(admin, path + "/checks", Map.of()).path("checkId").asText();
        machine(n, "/heartbeat", healthy, 200);
        var report = new java.util.HashMap<String, Object>(healthy);
        report.put("checkResult", Map.of("checkId", check, "passed", true));
        machine(n, "/heartbeat", report, 200);
        var updated =
                mockMvc.perform(
                                patch(path)
                                        .header("Authorization", "Bearer " + admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"revision\":1,\"state\":\"ENABLED\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(updated)).isEqualTo(200);
        return n;
    }

    private long createAlgoQuestion(String token, String pool) throws Exception {
        long qId =
                adminPost(
                                token,
                                "/questions",
                                Map.of(
                                        "title",
                                        "Algo_" + pool + "_" + random().substring(0, 8),
                                        "description",
                                        "Fixture question",
                                        "difficulty",
                                        "EASY",
                                        "languages",
                                        pool))
                        .path("id")
                        .asLong();
        adminPost(
                token,
                "/questions/" + qId + "/test-cases",
                Map.of("input", "1 2", "expectedOutput", "3", "isSample", 1));
        return qId;
    }

    private long createDbQuestion(String token) throws Exception {
        var req = new DbQuestionRequest();
        req.setTitle("Db_Q_" + random().substring(0, 8));
        req.setDescription("Fixture DB question");
        req.setType("SELECT");
        req.setSchemaSql(
                "CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(32), dept VARCHAR(16), salary DECIMAL(10,2));");
        req.setReferenceSql("SELECT id, name FROM emp WHERE dept = 'RD'");
        var tc = new DbTestCaseRequest();
        tc.setSeedSql("INSERT INTO emp VALUES (1,'Alice','RD',10000.00),(2,'Bob','HR',9000.00);");
        tc.setIsSample(1);
        req.setTestCases(List.of(tc));

        var resp = adminPost(token, "/db-questions", req);
        return resp.path("id").asLong();
    }

    private long submitAlgo(String token, long qId, String pool) throws Exception {
        return adminPost(
                        token,
                        "/submissions",
                        Map.of(
                                "questionId", qId,
                                "language", pool,
                                "codeContent", Map.of("main", "print(3)")))
                .path("submissionId")
                .asLong();
    }

    private long submitAlgoWithPayload(String token, Map<String, ?> payload) throws Exception {
        return adminPost(token, "/submissions", payload).path("submissionId").asLong();
    }

    private long submitSql(String token, long qId, String sql) throws Exception {
        return adminPost(token, "/db-questions/" + qId + "/submit", Map.of("sql", sql))
                .path("submissionId")
                .asLong();
    }

    private long submitSqlWithPayload(String token, long qId, Map<String, ?> payload)
            throws Exception {
        return adminPost(token, "/db-questions/" + qId + "/submit", payload)
                .path("submissionId")
                .asLong();
    }

    private JsonNode claim(Node n) throws Exception {
        return machine(n, "/jobs/claim", Map.of("slots", 1), 200).path("jobs");
    }

    private static Map<String, Object> accepted(JsonNode job) {
        List<Map<String, Object>> caseReports = new ArrayList<>();
        JsonNode cases = job.path("manifest").path("cases");
        for (JsonNode c : cases) {
            caseReports.add(
                    Map.of(
                            "caseId",
                            c.path("caseId").asLong(),
                            "status",
                            "ACCEPTED",
                            "timeMs",
                            10,
                            "memoryKb",
                            1024));
        }
        return Map.of(
                "leaseEpoch", job.path("leaseEpoch").asLong(),
                "status", "ACCEPTED",
                "result",
                        Map.of(
                                "score", 100,
                                "timeUsedMs", 20,
                                "memoryUsedKb", 2048,
                                "testCasesReport", caseReports));
    }

    private long createExamWithObjectiveAndCode(String token) throws Exception {
        MvcResult obj =
                mockMvc.perform(
                                post("/objective-questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "type", "SINGLE",
                                                                "content", "1 + 1 = ?",
                                                                "options", List.of("A. 1", "B. 2"),
                                                                "answer", "B",
                                                                "score", 5))))
                        .andExpect(status().isOk())
                        .andReturn();
        long objId = data(obj).path("id").asLong();

        long codeQid = createAlgoQuestion(token, "cpp");

        MvcResult exam =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "Exam_" + random().substring(0, 8),
                                                                "description",
                                                                "Exam test",
                                                                "durationMinutes",
                                                                60,
                                                                "mode",
                                                                "OI",
                                                                "status",
                                                                1,
                                                                "startTime",
                                                                LocalDateTime.now().minusMinutes(1),
                                                                "questions",
                                                                List.of(
                                                                        Map.of(
                                                                                "questionType",
                                                                                "OBJECTIVE",
                                                                                "questionId",
                                                                                objId,
                                                                                "score",
                                                                                5),
                                                                        Map.of(
                                                                                "questionType",
                                                                                "ALGO",
                                                                                "questionId",
                                                                                codeQid,
                                                                                "score",
                                                                                20))))))
                        .andExpect(status().isOk())
                        .andReturn();
        return data(exam).path("id").asLong();
    }

    private long examQuestionId(String token, long examId, String type) throws Exception {
        MvcResult enter =
                mockMvc.perform(
                                get("/exams/" + examId + "/enter")
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        for (JsonNode q : data(enter).path("questions")) {
            if (q.path("questionType").asText().equals(type)) {
                return q.path("id").asLong();
            }
        }
        throw new IllegalStateException("未找到 " + type + " 快照条目");
    }

    private void saveAnswer(String token, long examId, long eqId, Map<String, Object> body)
            throws Exception {
        mockMvc.perform(
                        put("/exams/" + examId + "/answers")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private String registerStudent() throws Exception {
        String username = "stu_" + random().substring(0, 8);
        mockMvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "username",
                                                        username,
                                                        "email",
                                                        username + "@example.com",
                                                        "password",
                                                        "pass123456"))))
                .andExpect(status().isOk());
        return loginToken(username, "pass123456");
    }

    private JsonNode adminPost(String token, String path, Object body) throws Exception {
        var response =
                mockMvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(response)).isEqualTo(200);
        return data(response);
    }

    private JsonNode machine(Node n, String path, Object body, int expected) throws Exception {
        var response =
                mockMvc.perform(
                                post(INTERNAL + path)
                                        .header("Authorization", "Bearer " + n.token())
                                        .header("X-Judge-Session-Epoch", n.epoch())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().is(expected))
                        .andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private static String random() {
        return UUID.randomUUID().toString();
    }
}
