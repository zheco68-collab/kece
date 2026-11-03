package com.kece.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.dto.TestCaseResultVO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 数据库 OJ 接口集成测试。
 *
 * <p>覆盖题目 CRUD、标准答案脱敏（{@code referenceSql}/{@code verifySql} 仅出题人可见）、提交入队与「人人只看自己」的记录页。 判题本身由 Go
 * 判题机执行，此处不启动判题机，提交停留在 PENDING，因此只断言入队任务与记录归属，不断言评测结果。
 */
class DbQuestionIntegrationTest extends AbstractIntegrationTest {

    private static final String SQL_QUEUE_KEY = "judge:sql:queue";

    private static final String EMP_SCHEMA =
            "CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(32), dept VARCHAR(16), salary DECIMAL(10,2));";
    private static final String EMP_SEED =
            "INSERT INTO emp VALUES (1,'Alice','RD',10000.00),(2,'Bob','RD',12000.00),(3,'Cara','HR',9000.00);";
    private static final String EMP_SEED_2 =
            "INSERT INTO emp VALUES (1,'Dan','RD',8000.00),(2,'Eve','HR',9500.00);";
    private static final String SELECT_REF = "SELECT id, name FROM emp WHERE dept = 'RD'";
    private static final String DML_REF = "UPDATE emp SET salary = salary * 1.1 WHERE dept = 'RD'";
    private static final String DML_VERIFY = "SELECT id, name, salary FROM emp ORDER BY id";

    /** 判题机写回的结果集 JSON（比对展示用，内容不必真实）。 */
    private static final String EXPECTED_RESULT_SET =
            "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}],\"rows\":[[\"1\"],[\"2\"]]}";

    private static final String ACTUAL_RESULT_SET =
            "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}],\"rows\":[[\"1\"],[\"3\"]]}";

    @Autowired private StringRedisTemplate redis;

    // ---------- helpers ----------

    private String teacherToken() throws Exception {
        return loginToken("teacher", "teacher123");
    }

    private String uniqueTitle(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 测试点请求体：现在只描述一套初始数据。 */
    private Map<String, Object> caseBody(String seedSql, int isSample) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seedSql", seedSql);
        body.put("isSample", isSample);
        return body;
    }

    private Map<String, Object> selectQuestion(String title) {
        return questionBody(
                title,
                "SELECT",
                SELECT_REF,
                null,
                1,
                List.of(caseBody(EMP_SEED, 1), caseBody(EMP_SEED_2, 0)));
    }

    private Map<String, Object> dmlQuestion(String title) {
        return questionBody(
                title,
                "DML",
                DML_REF,
                DML_VERIFY,
                1,
                List.of(caseBody(EMP_SEED, 1), caseBody(EMP_SEED_2, 0)));
    }

    /** 题目请求体：建表语句与标准答案在题目级，测试点只带数据。 */
    private Map<String, Object> questionBody(
            String title,
            String type,
            String referenceSql,
            String verifySql,
            int questionStatus,
            List<Map<String, Object>> cases) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", "题目描述：" + title);
        body.put("type", type);
        body.put("schemaSql", EMP_SCHEMA);
        body.put("referenceSql", referenceSql);
        if (verifySql != null) {
            body.put("verifySql", verifySql);
        }
        body.put("orderSensitive", false);
        body.put("status", questionStatus);
        body.put("testCases", cases);
        return body;
    }

    private long createQuestion(String token, Map<String, Object> body) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/db-questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result).path("id").asLong();
    }

    private JsonNode getQuestion(long id, String token) throws Exception {
        var request = get("/db-questions/" + id);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result);
    }

    private JsonNode listQuestions(String token, String keyword, boolean all) throws Exception {
        var request =
                get("/db-questions").param("keyword", keyword).param("all", String.valueOf(all));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result);
    }

    private String registerStudent() throws Exception {
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
        return loginToken(username, "pass123456");
    }

    private JsonNode submit(long questionId, String token, String sql) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/db-questions/" + questionId + "/submit")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of("sql", sql))))
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result);
    }

    private JsonNode getSubmission(long submissionId, String token) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/db-submissions/" + submissionId)
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result);
    }

    /**
     * 判题机未启动时，直接往 Redis 状态缓存写一份终态结果：两个测试点都带比对结果，专测脱敏。
     *
     * @return 逐用例报告，顺序与传参一致
     */
    private void cacheTerminalResult(long submissionId, long sampleCaseId, long hiddenCaseId)
            throws Exception {
        SubmissionVO vo =
                SubmissionVO.builder()
                        .submissionId(submissionId)
                        .status("WRONG_ANSWER")
                        .result(
                                SubmissionResultVO.builder()
                                        .score(0)
                                        .timeUsedMs(12)
                                        .testCasesReport(
                                                List.of(
                                                        caseResult(sampleCaseId, 5),
                                                        caseResult(hiddenCaseId, 7)))
                                        .build())
                        .build();
        redis.opsForValue()
                .set("dbsub:status:" + submissionId, objectMapper.writeValueAsString(vo));
    }

    private TestCaseResultVO caseResult(long caseId, int timeMs) {
        return TestCaseResultVO.builder()
                .caseId(caseId)
                .status("WRONG_ANSWER")
                .timeMs(timeMs)
                .expectedOutput(EXPECTED_RESULT_SET)
                .actualOutput(ACTUAL_RESULT_SET)
                .build();
    }

    private JsonNode listSubmissions(String token, String statusFilter) throws Exception {
        var request = get("/db-submissions");
        if (statusFilter != null) {
            request = request.param("status", statusFilter);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result);
    }

    // ---------- 题目 CRUD ----------

    @Test
    void createShouldReturnQuestionWithOrderedCases() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("cr")));

        JsonNode question = getQuestion(id, token);
        assertThat(question.path("type").asText()).isEqualTo("SELECT");
        assertThat(question.path("dialect").asText()).isEqualTo("MYSQL");
        assertThat(question.path("status").asInt()).isEqualTo(1);
        // 建表语句与标准答案在题目级。
        assertThat(question.path("schemaSql").asText()).isEqualTo(EMP_SCHEMA);
        assertThat(question.path("referenceSql").asText()).isEqualTo(SELECT_REF);
        assertThat(question.path("orderSensitive").asBoolean()).isFalse();

        JsonNode cases = question.path("testCases");
        assertThat(cases.size()).isEqualTo(2);
        // caseOrder 由后端按提交顺序重排，与前端数组下标一致。
        assertThat(cases.get(0).path("caseOrder").asInt()).isZero();
        assertThat(cases.get(1).path("caseOrder").asInt()).isEqualTo(1);
        // 测试点只带数据，不再有 ddl/referenceSql/verifySql。
        assertThat(cases.get(0).path("seedSql").asText()).isEqualTo(EMP_SEED);
        assertThat(cases.get(0).has("ddl")).isFalse();
        assertThat(cases.get(0).has("referenceSql")).isFalse();
    }

    @Test
    void isSampleShouldSerializeUnderItsOwnName() throws Exception {
        // 回归保护：boolean 字段若命名为 isSample，Lombok 的 isSample() 会被 Jackson 推导为属性 sample。
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("smp")));

        JsonNode cases = getQuestion(id, token).path("testCases");
        assertThat(cases.get(0).path("isSample").asInt()).isEqualTo(1);
        assertThat(cases.get(1).path("isSample").asInt()).isZero();
        assertThat(cases.get(0).has("sample")).isFalse();
    }

    @Test
    void updateShouldReplaceTestCases() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("upd")));

        String newTitle = uniqueTitle("upd2");
        Map<String, Object> updated = dmlQuestion(newTitle);
        updated.put("testCases", List.of(caseBody(EMP_SEED_2, 0)));
        MvcResult result =
                mockMvc.perform(
                                put("/db-questions/" + id)
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(updated)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);

        JsonNode question = getQuestion(id, token);
        assertThat(question.path("title").asText()).isEqualTo(newTitle);
        assertThat(question.path("type").asText()).isEqualTo("DML");
        assertThat(question.path("referenceSql").asText()).isEqualTo(DML_REF);
        assertThat(question.path("verifySql").asText()).isEqualTo(DML_VERIFY);
        // 测试点整体替换：2 条变 1 条。
        JsonNode cases = question.path("testCases");
        assertThat(cases.size()).isEqualTo(1);
        assertThat(cases.get(0).path("seedSql").asText()).isEqualTo(EMP_SEED_2);
    }

    @Test
    void deleteShouldRemoveQuestion() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("del")));

        MvcResult deleted =
                mockMvc.perform(
                                delete("/db-questions/" + id)
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(deleted)).isEqualTo(200);

        MvcResult after = mockMvc.perform(get("/db-questions/" + id)).andReturn();
        assertThat(code(after)).isEqualTo(4004);
    }

    @Test
    void deleteQuestionWithSubmission_shouldBeRejected() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("del-protect")));

        // 学生提交 SQL（判题机未启动，记录停留在 PENDING 但已入库）
        String student = registerStudent();
        submit(id, student, "SELECT id FROM emp");

        // 有提交记录的题：出题人（非管理员）禁删
        MvcResult deleted =
                mockMvc.perform(
                                delete("/db-questions/" + id)
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(deleted)).isEqualTo(4001);
        assertThat(body(deleted).path("message").asText()).isEqualTo("该题目已有提交记录，仅管理员可强制删除");

        // 管理员可强制删除（提交记录级联清理）
        String admin = loginToken("admin", "admin123");
        MvcResult forceDelete =
                mockMvc.perform(
                                delete("/db-questions/" + id)
                                        .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forceDelete)).isEqualTo(200);

        MvcResult after = mockMvc.perform(get("/db-questions/" + id)).andReturn();
        assertThat(code(after)).isEqualTo(4004);
    }

    @Test
    void createShouldBeForbiddenForRegularUser() throws Exception {
        String student = registerStudent();
        MvcResult result =
                mockMvc.perform(
                                post("/db-questions")
                                        .header("Authorization", "Bearer " + student)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        selectQuestion(uniqueTitle("no")))))
                        .andReturn();
        assertThat(code(result)).isEqualTo(4003);
    }

    @Test
    void createWithoutReferenceSqlShouldFailValidation() throws Exception {
        String token = teacherToken();
        Map<String, Object> body =
                questionBody(
                        uniqueTitle("vld"),
                        "SELECT",
                        "  ",
                        null,
                        1,
                        List.of(caseBody(EMP_SEED, 0)));
        MvcResult result =
                mockMvc.perform(
                                post("/db-questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andReturn();
        assertThat(code(result)).isEqualTo(4001);
    }

    @Test
    void createWithoutSeedShouldFailValidation() throws Exception {
        String token = teacherToken();
        Map<String, Object> body =
                questionBody(
                        uniqueTitle("vld2"),
                        "SELECT",
                        SELECT_REF,
                        null,
                        1,
                        List.of(caseBody("   ", 0)));
        MvcResult result =
                mockMvc.perform(
                                post("/db-questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andReturn();
        assertThat(code(result)).isEqualTo(4001);
    }

    // ---------- 标准答案脱敏 ----------

    @Test
    void detailShouldHideReferenceSqlForRegularUser() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, dmlQuestion(uniqueTitle("sec")));
        String student = registerStudent();

        // 游客与普通用户：标准答案不可见，但建表语句与样例数据可见（否则无法作答）。
        for (String viewer : new String[] {null, student}) {
            JsonNode question = getQuestion(id, viewer);
            assertThat(question.path("referenceSql").isNull()).isTrue();
            assertThat(question.path("verifySql").isNull()).isTrue();
            assertThat(question.path("schemaSql").asText()).isEqualTo(EMP_SCHEMA);
            JsonNode cases = question.path("testCases");
            assertThat(cases.size()).isEqualTo(2);
            assertThat(cases.get(0).path("seedSql").asText()).isEqualTo(EMP_SEED);
            assertThat(cases.get(0).path("isSample").asInt()).isEqualTo(1);
        }

        // 出题人可见标准答案与校验查询。
        JsonNode byTeacher = getQuestion(id, token);
        assertThat(byTeacher.path("referenceSql").asText()).isEqualTo(DML_REF);
        assertThat(byTeacher.path("verifySql").asText()).isEqualTo(DML_VERIFY);
    }

    @Test
    void privateQuestionShouldBeHiddenFromRegularUser() throws Exception {
        String token = teacherToken();
        String title = uniqueTitle("priv");
        long id =
                createQuestion(
                        token,
                        questionBody(
                                title,
                                "SELECT",
                                SELECT_REF,
                                null,
                                0,
                                List.of(caseBody(EMP_SEED, 1))));

        // 未公开题目对普通用户既不出现在列表，也不能按 id 直接打开。
        assertThat(listQuestions(null, title, false).path("total").asLong()).isZero();
        MvcResult guestDetail = mockMvc.perform(get("/db-questions/" + id)).andReturn();
        assertThat(code(guestDetail)).isEqualTo(4004);

        // 出题人带 all=true 可见。
        assertThat(listQuestions(token, title, true).path("total").asLong()).isEqualTo(1);
    }

    // ---------- 提交与记录 ----------

    @Test
    void submitShouldPushTaskToRedisQueue() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("que")));
        String student = registerStudent();

        long submissionId =
                submit(id, student, "SELECT id, name FROM emp").path("submissionId").asLong();

        // 集成测试不启动判题机，任务应留在队列里，且携带题目的建表/答案脚本供判题机使用。
        List<String> tasks = redis.opsForList().range(SQL_QUEUE_KEY, 0, -1);
        assertThat(tasks).isNotNull();
        String task =
                tasks.stream()
                        .filter(t -> t.contains("\"submissionId\":" + submissionId))
                        .findFirst()
                        .orElse(null);
        assertThat(task).as("提交任务应已压入 %s", SQL_QUEUE_KEY).isNotNull();
        assertThat(task).contains("\"type\":\"sql\"");
        assertThat(task).contains("\"answer.sql\":\"SELECT id, name FROM emp\"");
        assertThat(task).contains("\"schemaSql\":\"" + EMP_SCHEMA + "\"");
        assertThat(task).contains("\"referenceSql\":\"" + SELECT_REF + "\"");
        assertThat(task).contains("\"timeLimitMs\":5000");
        // 测试点只带数据，不再重复下发建表语句与答案。
        assertThat(task).contains("\"seedSql\":\"" + EMP_SEED + "\"");
        assertThat(task).doesNotContain("\"ddl\"");
    }

    @Test
    void submitShouldReturnPendingWithTotalCases() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("pnd")));
        String student = registerStudent();

        JsonNode pending = submit(id, student, "SELECT id FROM emp");
        assertThat(pending.path("submissionId").asLong()).isPositive();
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("progress").path("totalCases").asInt()).isEqualTo(2);
        assertThat(pending.path("progress").path("finishedCases").asInt()).isZero();
    }

    @Test
    void submitShouldRejectBlankSql() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("blk")));
        String student = registerStudent();

        MvcResult result =
                mockMvc.perform(
                                post("/db-questions/" + id + "/submit")
                                        .header("Authorization", "Bearer " + student)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of("sql", "   "))))
                        .andReturn();
        assertThat(code(result)).isEqualTo(4001);
    }

    // ---------- 结果脱敏 ----------

    @Test
    void resultShouldRevealDiffOnlyForSampleCase() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("diff")));
        JsonNode cases = getQuestion(id, token).path("testCases");
        long sampleCaseId = cases.get(0).path("id").asLong();
        long hiddenCaseId = cases.get(1).path("id").asLong();

        String student = registerStudent();
        long submissionId = submit(id, student, "SELECT id FROM emp").path("submissionId").asLong();
        cacheTerminalResult(submissionId, sampleCaseId, hiddenCaseId);

        // 样例测试点的数据本就随题面公开，比对结果保留，供答题者自助排查。
        JsonNode report =
                getSubmission(submissionId, student).path("result").path("testCasesReport");
        assertThat(report.get(0).path("caseId").asLong()).isEqualTo(sampleCaseId);
        assertThat(report.get(0).path("expectedOutput").asText()).isEqualTo(EXPECTED_RESULT_SET);
        assertThat(report.get(0).path("actualOutput").asText()).isEqualTo(ACTUAL_RESULT_SET);

        // 非样例测试点只留状态，比对结果置空，避免反推答案。
        assertThat(report.get(1).path("caseId").asLong()).isEqualTo(hiddenCaseId);
        assertThat(report.get(1).path("status").asText()).isEqualTo("WRONG_ANSWER");
        assertThat(report.get(1).path("expectedOutput").isNull()).isTrue();
        assertThat(report.get(1).path("actualOutput").isNull()).isTrue();

        // 管理员不受限：逐用例比对结果全部可见。
        JsonNode asAdmin =
                getSubmission(submissionId, loginToken("admin", "admin123"))
                        .path("result")
                        .path("testCasesReport");
        assertThat(asAdmin.get(0).path("expectedOutput").asText()).isEqualTo(EXPECTED_RESULT_SET);
        assertThat(asAdmin.get(1).path("expectedOutput").asText()).isEqualTo(EXPECTED_RESULT_SET);
        assertThat(asAdmin.get(1).path("actualOutput").asText()).isEqualTo(ACTUAL_RESULT_SET);
    }

    @Test
    void submissionListShouldOnlyContainOwnRecords() throws Exception {
        String token = teacherToken();
        String title = uniqueTitle("own");
        long id = createQuestion(token, selectQuestion(title));
        String student = registerStudent();

        long submissionId =
                submit(id, student, "SELECT id, name FROM emp").path("submissionId").asLong();

        JsonNode mine = listSubmissions(student, null);
        assertThat(mine.path("total").asLong()).isEqualTo(1);
        JsonNode record = mine.path("list").get(0);
        assertThat(record.path("submissionId").asLong()).isEqualTo(submissionId);
        assertThat(record.path("questionId").asLong()).isEqualTo(id);
        assertThat(record.path("questionTitle").asText()).isEqualTo(title);
        assertThat(record.path("status").asText()).isEqualTo("PENDING");
        assertThat(record.path("sqlContent").asText()).isEqualTo("SELECT id, name FROM emp");

        // 状态筛选命中/落空。
        assertThat(listSubmissions(student, "PENDING").path("total").asLong()).isEqualTo(1);
        assertThat(listSubmissions(student, "ACCEPTED").path("total").asLong()).isZero();

        // 另一位用户（含出题人）看不到别人的提交。
        assertThat(listSubmissions(registerStudent(), null).path("total").asLong()).isZero();
        assertThat(listSubmissions(token, null).path("total").asLong()).isZero();
    }

    @Test
    void submissionDetailShouldBeVisibleOnlyToOwner() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, selectQuestion(uniqueTitle("own2")));
        String student = registerStudent();
        long submissionId = submit(id, student, "SELECT id FROM emp").path("submissionId").asLong();

        MvcResult asOwner =
                mockMvc.perform(
                                get("/db-submissions/" + submissionId)
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(asOwner)).isEqualTo(200);
        assertThat(data(asOwner).path("status").asText()).isEqualTo("PENDING");

        MvcResult asOther =
                mockMvc.perform(
                                get("/db-submissions/" + submissionId)
                                        .header("Authorization", "Bearer " + registerStudent()))
                        .andReturn();
        assertThat(code(asOther)).isEqualTo(4003);

        MvcResult anonymous = mockMvc.perform(get("/db-submissions/" + submissionId)).andReturn();
        assertThat(code(anonymous)).isEqualTo(4002);
    }
}
