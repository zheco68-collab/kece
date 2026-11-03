package com.kece.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 考试集成测试：创建/进入/倒计时/到点自动锁定/取消（考试本身即一次考试，无场次中间层）。 */
class ExamSessionIntegrationTest extends AbstractIntegrationTest {

    private String teacherToken() throws Exception {
        return loginToken("teacher", "teacher123");
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

    /** 创建带一道算法题的考试，startTime 控制考试开始时间。 */
    private long createExamWithQuestion(String token, LocalDateTime startTime) throws Exception {
        MvcResult q =
                mockMvc.perform(
                                post("/questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "考试测试_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                "description",
                                                                "## 题目描述",
                                                                "difficulty",
                                                                "EASY"))))
                        .andExpect(status().isOk())
                        .andReturn();
        long qid = data(q).path("id").asLong();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", "考试测试卷_" + UUID.randomUUID().toString().substring(0, 8));
        body.put("description", "d");
        body.put("durationMinutes", 60);
        body.put("mode", "OI");
        body.put("status", 1);
        if (startTime != null) {
            body.put("startTime", startTime);
        }
        body.put(
                "questions",
                List.of(Map.of("questionType", "ALGO", "questionId", qid, "score", 20)));
        MvcResult exam =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(exam)).isEqualTo(200);
        return data(exam).path("id").asLong();
    }

    @Test
    void createAndEnterDuringWindow_shouldReturnCountdown() throws Exception {
        String token = teacherToken();
        long examId = createExamWithQuestion(token, LocalDateTime.now().minusMinutes(1));

        // 考生进入：状态推进为 ONGOING，返回倒计时与题目快照
        String student = registerStudent();
        MvcResult enter =
                mockMvc.perform(
                                get("/exams/" + examId + "/enter")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(enter)).isEqualTo(200);
        JsonNode enterData = data(enter);
        assertThat(enterData.path("status").asText()).isEqualTo("ONGOING");
        assertThat(enterData.path("remainingSeconds").asLong()).isGreaterThan(0);
        assertThat(enterData.path("questions").size()).isEqualTo(1);
        assertThat(enterData.path("examTitle").asText()).startsWith("考试测试卷_");
    }

    @Test
    void enterBeforeStart_shouldReject() throws Exception {
        String token = teacherToken();
        long examId = createExamWithQuestion(token, LocalDateTime.now().plusMinutes(5));

        String student = registerStudent();
        MvcResult enter =
                mockMvc.perform(
                                get("/exams/" + examId + "/enter")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(enter)).isEqualTo(4001);
        assertThat(body(enter).path("message").asText()).isEqualTo("考试尚未开始");
    }

    @Test
    void enterAfterEnd_shouldAutoLock() throws Exception {
        String token = teacherToken();
        long examId = createExamWithQuestion(token, LocalDateTime.now().minusMinutes(90));

        String student = registerStudent();
        MvcResult enter =
                mockMvc.perform(
                                get("/exams/" + examId + "/enter")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(enter)).isEqualTo(4001);
        assertThat(body(enter).path("message").asText()).isEqualTo("考试已结束，已自动交卷");
    }

    @Test
    void cancelShouldBlockEnter() throws Exception {
        String token = teacherToken();
        long examId = createExamWithQuestion(token, LocalDateTime.now().plusMinutes(5));

        MvcResult cancelled =
                mockMvc.perform(
                                delete("/exams/" + examId + "/cancel")
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(cancelled)).isEqualTo(200);

        String student = registerStudent();
        MvcResult enter =
                mockMvc.perform(
                                get("/exams/" + examId + "/enter")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(enter)).isEqualTo(4001);
        assertThat(body(enter).path("message").asText()).isEqualTo("该考试已取消");
    }

    @Test
    void createExamWithEmptyQuestions_shouldReject() throws Exception {
        String token = teacherToken();
        MvcResult exam =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "空卷_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                "description",
                                                                "d",
                                                                "durationMinutes",
                                                                60,
                                                                "mode",
                                                                "OI",
                                                                "status",
                                                                1,
                                                                "questions",
                                                                List.of()))))
                        .andExpect(status().isOk())
                        .andReturn();
        // 空卷创建本身被校验拦截
        assertThat(code(exam)).isEqualTo(4001);
    }

    @Test
    void deleteExamWithSubmissions_shouldReject() throws Exception {
        String token = teacherToken();
        long examId = createExamWithQuestion(token, LocalDateTime.now().minusMinutes(1));

        // 考生进入并保存作答
        String student = registerStudent();
        MvcResult enter =
                mockMvc.perform(
                                get("/exams/" + examId + "/enter")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        long eqId = data(enter).path("questions").get(0).path("id").asLong();
        mockMvc.perform(
                        put("/exams/" + examId + "/answers")
                                .header("Authorization", "Bearer " + student)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "examQuestionId",
                                                        eqId,
                                                        "answer",
                                                        "{\"main.cpp\":\"int main(){}\"}",
                                                        "language",
                                                        "cpp"))))
                .andExpect(status().isOk());

        // 有作答记录后：出题人（非管理员）删除被拒绝
        MvcResult del =
                mockMvc.perform(
                                delete("/exams/" + examId)
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(del)).isEqualTo(4001);
        assertThat(body(del).path("message").asText()).isEqualTo("该考试已有作答记录，仅管理员可强制删除");

        // 管理员可强制删除（作答记录级联清理）
        String admin = loginToken("admin", "admin123");
        MvcResult forceDel =
                mockMvc.perform(
                                delete("/exams/" + examId)
                                        .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forceDel)).isEqualTo(200);

        // 删除后考试不存在
        MvcResult detail =
                mockMvc.perform(get("/exams/" + examId).header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(detail)).isEqualTo(4004);
    }
}
