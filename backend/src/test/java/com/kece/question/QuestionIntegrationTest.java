package com.kece.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 算法题库接口集成测试。 */
class QuestionIntegrationTest extends AbstractIntegrationTest {

    private String teacherToken() throws Exception {
        return loginToken("teacher", "teacher123");
    }

    private long createQuestion(String token, String title) throws Exception {
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
                                                                "EASY"))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result).path("id").asLong();
    }

    @Test
    void teacherCreateListDetailDelete_shouldSucceed() throws Exception {
        String token = teacherToken();
        String title = "两数之和_" + UUID.randomUUID().toString().substring(0, 8);
        long id = createQuestion(token, title);

        // 列表可检索到
        MvcResult list = mockMvc.perform(get("/questions")).andExpect(status().isOk()).andReturn();
        assertThat(code(list)).isEqualTo(200);
        assertThat(data(list).path("total").asLong()).isGreaterThanOrEqualTo(1);

        // 详情
        MvcResult detail =
                mockMvc.perform(get("/questions/" + id)).andExpect(status().isOk()).andReturn();
        assertThat(data(detail).path("title").asText()).isEqualTo(title);
        assertThat(data(detail).path("difficulty").asText()).isEqualTo("EASY");

        // 新增样例测试用例
        MvcResult addCase =
                mockMvc.perform(
                                post("/questions/" + id + "/test-cases")
                                        .header("Authorization", "Bearer " + token)
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
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(addCase)).isEqualTo(200);

        // 详情应携带样例用例
        MvcResult detailWithCase =
                mockMvc.perform(get("/questions/" + id)).andExpect(status().isOk()).andReturn();
        JsonNode samples = data(detailWithCase).path("sampleTestCases");
        assertThat(samples.size()).isEqualTo(1);
        assertThat(samples.get(0).path("expectedOutput").asText()).isEqualTo("3");

        // 删除
        MvcResult deleteResult =
                mockMvc.perform(
                                delete("/questions/" + id)
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(deleteResult)).isEqualTo(200);
    }

    @Test
    void guestCreateQuestion_shouldReturnForbidden() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/questions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "t",
                                                                "description",
                                                                "d",
                                                                "difficulty",
                                                                "EASY"))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(4002);
    }

    @Test
    void regularUserCreateQuestion_shouldReturnForbidden() throws Exception {
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
        String token = loginToken(username, "pass123456");

        MvcResult result =
                mockMvc.perform(
                                post("/questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "t",
                                                                "description",
                                                                "d",
                                                                "difficulty",
                                                                "EASY"))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(4003);
    }

    @Test
    void deleteQuestionWithSubmission_shouldBeRejected() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, "删除保护_" + UUID.randomUUID().toString().substring(0, 8));

        // 学生提交代码（判题机未启动，记录停留在 PENDING 但已入库）
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
        mockMvc.perform(
                        post("/submissions")
                                .header("Authorization", "Bearer " + studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "questionId",
                                                        id,
                                                        "language",
                                                        "cpp",
                                                        "codeContent",
                                                        Map.of(
                                                                "main.cpp",
                                                                "int main(){return 0;}")))))
                .andExpect(status().isOk())
                .andReturn();

        // 有提交记录的题：出题人（非管理员）禁删
        MvcResult deleteResult =
                mockMvc.perform(
                                delete("/questions/" + id)
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(deleteResult)).isEqualTo(4001);
        assertThat(body(deleteResult).path("message").asText()).isEqualTo("该题目已有提交记录，仅管理员可强制删除");

        // 管理员可强制删除（提交记录级联清理）
        String admin = loginToken("admin", "admin123");
        MvcResult forceDelete =
                mockMvc.perform(
                                delete("/questions/" + id)
                                        .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forceDelete)).isEqualTo(200);

        MvcResult after =
                mockMvc.perform(get("/questions/" + id)).andExpect(status().isOk()).andReturn();
        assertThat(code(after)).isEqualTo(4004);
    }

    @Test
    void rejudgeInFlightSubmission_shouldReturnParamError() throws Exception {
        String token = teacherToken();
        long id = createQuestion(token, "重判并发_" + UUID.randomUUID().toString().substring(0, 8));

        // 学生提交代码（判题机未启动，记录停留在 PENDING）
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
        MvcResult submit =
                mockMvc.perform(
                                post("/submissions")
                                        .header("Authorization", "Bearer " + studentToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "questionId",
                                                                id,
                                                                "language",
                                                                "cpp",
                                                                "codeContent",
                                                                Map.of(
                                                                        "main.cpp",
                                                                        "int main(){return 0;}")))))
                        .andExpect(status().isOk())
                        .andReturn();
        long submissionId = data(submit).path("submissionId").asLong();

        // 评测中（PENDING）拒绝重判
        MvcResult rejudge =
                mockMvc.perform(
                                post("/admin/submissions/" + submissionId + "/rejudge")
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(rejudge)).isEqualTo(4001);
        assertThat(body(rejudge).path("message").asText()).isEqualTo("该提交正在评测中，请稍后再试");
    }
}
