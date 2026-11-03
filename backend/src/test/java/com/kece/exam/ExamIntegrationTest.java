package com.kece.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 试卷与组卷接口集成测试：组卷快照、更新替换、删除、权限与可见性。 */
class ExamIntegrationTest extends AbstractIntegrationTest {

    private String teacherToken() throws Exception {
        return loginToken("teacher", "teacher123");
    }

    private String adminToken() throws Exception {
        return loginToken("admin", "admin123");
    }

    private long createAlgoQuestion(String token, String title, Integer status) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                title,
                                                                "description",
                                                                "## 题目描述",
                                                                "difficulty",
                                                                "EASY",
                                                                "status",
                                                                status == null ? 1 : status))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result).path("id").asLong();
    }

    private long createObjectiveQuestion(String token) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/objective-questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "type",
                                                                "SINGLE",
                                                                "content",
                                                                "1 + 1 = ?",
                                                                "options",
                                                                List.of("A. 1", "B. 2"),
                                                                "answer",
                                                                "B",
                                                                "score",
                                                                5))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result).path("id").asLong();
    }

    private Map<String, Object> examBody(
            String title, int duration, List<Map<String, Object>> questions) {
        return Map.of(
                "title",
                title,
                "description",
                "组卷测试",
                "durationMinutes",
                duration,
                "mode",
                "OI",
                "status",
                1,
                "questions",
                questions);
    }

    private long createExam(String token, Map<String, Object> body) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result).path("id").asLong();
    }

    @Test
    void createWithSnapshots_shouldLockTitleAtComposeTime() throws Exception {
        String token = teacherToken();
        String qTitle = "快照测试_" + UUID.randomUUID().toString().substring(0, 8);
        long qid = createAlgoQuestion(token, qTitle, 1);
        long objId = createObjectiveQuestion(token);

        long examId =
                createExam(
                        token,
                        examBody(
                                "试卷_" + UUID.randomUUID().toString().substring(0, 8),
                                90,
                                List.of(
                                        Map.of(
                                                "questionType",
                                                "ALGO",
                                                "questionId",
                                                qid,
                                                "score",
                                                20),
                                        Map.of(
                                                "questionType",
                                                "OBJECTIVE",
                                                "questionId",
                                                objId,
                                                "score",
                                                5))));

        // 详情含按顺序排列的快照
        MvcResult detail =
                mockMvc.perform(get("/exams/" + examId)).andExpect(status().isOk()).andReturn();
        assertThat(code(detail)).isEqualTo(200);
        JsonNode questions = data(detail).path("questions");
        assertThat(questions.size()).isEqualTo(2);
        assertThat(questions.get(0).path("title").asText()).isEqualTo(qTitle);
        assertThat(questions.get(0).path("score").asInt()).isEqualTo(20);
        assertThat(questions.get(0).path("sortOrder").asInt()).isEqualTo(0);
        assertThat(questions.get(1).path("questionType").asText()).isEqualTo("OBJECTIVE");

        // 快照语义：改原题标题不影响已组卷
        mockMvc.perform(
                        put("/questions/" + qid)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "title",
                                                        "改了标题",
                                                        "description",
                                                        "## 题目描述",
                                                        "difficulty",
                                                        "EASY",
                                                        "status",
                                                        1))))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult after =
                mockMvc.perform(get("/exams/" + examId)).andExpect(status().isOk()).andReturn();
        assertThat(data(after).path("questions").get(0).path("title").asText()).isEqualTo(qTitle);
    }

    @Test
    void updateShouldReplaceQuestions() throws Exception {
        String token = teacherToken();
        long qid1 =
                createAlgoQuestion(token, "甲题_" + UUID.randomUUID().toString().substring(0, 8), 1);
        long qid2 =
                createAlgoQuestion(token, "乙题_" + UUID.randomUUID().toString().substring(0, 8), 1);
        long examId =
                createExam(
                        token,
                        examBody(
                                "更新测试_" + UUID.randomUUID().toString().substring(0, 8),
                                60,
                                List.of(
                                        Map.of(
                                                "questionType",
                                                "ALGO",
                                                "questionId",
                                                qid1,
                                                "score",
                                                10))));

        MvcResult updated =
                mockMvc.perform(
                                put("/exams/" + examId)
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        examBody(
                                                                "更新测试_2",
                                                                60,
                                                                List.of(
                                                                        Map.of(
                                                                                "questionType",
                                                                                "ALGO",
                                                                                "questionId",
                                                                                qid2,
                                                                                "score",
                                                                                30))))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(updated)).isEqualTo(200);
        JsonNode questions = data(updated).path("questions");
        assertThat(questions.size()).isEqualTo(1);
        assertThat(questions.get(0).path("questionId").asLong()).isEqualTo(qid2);
        assertThat(questions.get(0).path("score").asInt()).isEqualTo(30);
    }

    @Test
    void deleteShouldRemoveExam() throws Exception {
        String token = teacherToken();
        long qid =
                createAlgoQuestion(
                        token, "删除测试_" + UUID.randomUUID().toString().substring(0, 8), 1);
        long examId =
                createExam(
                        token,
                        examBody(
                                "删除测试卷_" + UUID.randomUUID().toString().substring(0, 8),
                                60,
                                List.of(
                                        Map.of(
                                                "questionType",
                                                "ALGO",
                                                "questionId",
                                                qid,
                                                "score",
                                                10))));

        MvcResult deleted =
                mockMvc.perform(
                                delete("/exams/" + examId)
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(deleted)).isEqualTo(200);

        MvcResult after = mockMvc.perform(get("/exams/" + examId)).andReturn();
        assertThat(code(after)).isEqualTo(4004);
    }

    @Test
    void guestAndRegularUserCreate_shouldBeForbidden() throws Exception {
        MvcResult guest =
                mockMvc.perform(
                                post("/exams")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        examBody("t", 60, List.of()))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(guest)).isEqualTo(4002);

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
        String studentToken = loginToken(username, "pass123456");
        MvcResult student =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + studentToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        examBody("t", 60, List.of()))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(student)).isEqualTo(4003);
    }

    @Test
    void privateQuestionComposeByOtherTeacher_shouldReject() throws Exception {
        // 管理员把一名新注册用户提升为 TEACHER
        String username = "t2_" + UUID.randomUUID().toString().substring(0, 8);
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
        String admin = adminToken();
        MvcResult users =
                mockMvc.perform(
                                get("/admin/users")
                                        .param("keyword", username)
                                        .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        long userId = data(users).path("list").get(0).path("id").asLong();
        mockMvc.perform(
                        put("/admin/users/" + userId + "/roles")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("roles", List.of("TEACHER")))))
                .andExpect(status().isOk())
                .andReturn();
        String teacher2 = loginToken(username, "pass123456");

        // teacher1 出一道私有题
        String token = teacherToken();
        long privateQid =
                createAlgoQuestion(token, "私有题_" + UUID.randomUUID().toString().substring(0, 8), 0);

        // teacher2 组卷引用该私有题 → 4001
        MvcResult result =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + teacher2)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        examBody(
                                                                "越权组卷_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                60,
                                                                List.of(
                                                                        Map.of(
                                                                                "questionType",
                                                                                "ALGO",
                                                                                "questionId",
                                                                                privateQid,
                                                                                "score",
                                                                                10))))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(4001);

        // teacher1 出一道公开题，teacher2 同样不能组卷（教师只能选自己出的题）
        long publicQid =
                createAlgoQuestion(token, "公开题_" + UUID.randomUUID().toString().substring(0, 8), 1);
        MvcResult teacher2PublicResult =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + teacher2)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        examBody(
                                                                "选他人公开题_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                60,
                                                                List.of(
                                                                        Map.of(
                                                                                "questionType",
                                                                                "ALGO",
                                                                                "questionId",
                                                                                publicQid,
                                                                                "score",
                                                                                10))))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(teacher2PublicResult)).isEqualTo(4001);

        // 管理员没有限制：可以选 teacher1 出的题进行组卷
        MvcResult adminResult =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        examBody(
                                                                "管理员组卷_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                60,
                                                                List.of(
                                                                        Map.of(
                                                                                "questionType",
                                                                                "ALGO",
                                                                                "questionId",
                                                                                publicQid,
                                                                                "score",
                                                                                10))))))
                        .andExpect(status().isOk())
                        .andReturn();
    }
}
