package com.kece.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kece.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 题解公开读取、直接发布、所有权和发布后管理的集成测试。 */
class SolutionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void publishUpdateHideAndRestore_shouldRespectVisibilityAndOwnership() throws Exception {
        String teacherToken = loginToken("teacher", "teacher123");
        long questionId = createPublicQuestion(teacherToken);
        String author = "solution_author_" + UUID.randomUUID().toString().substring(0, 8);
        String other = "solution_other_" + UUID.randomUUID().toString().substring(0, 8);
        String authorToken = registerAndLogin(author);
        String otherToken = registerAndLogin(other);
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> create =
                Map.of(
                        "questionType", "ALGO",
                        "questionId", questionId,
                        "title", "双指针判断回文串",
                        "contentMd", "## 思路\n```python\nprint('ok')\n```",
                        "clientRequestId", requestId);

        MvcResult published =
                mockMvc.perform(
                                post("/solutions")
                                        .header("Authorization", "Bearer " + authorToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(create)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(published)).isEqualTo(200);
        long solutionId = data(published).path("id").asLong();
        assertThat(data(published).path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(data(published).path("version").asLong()).isEqualTo(1L);

        MvcResult publicList =
                mockMvc.perform(get("/solutions?questionType=ALGO&questionId=" + questionId))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(publicList)).isEqualTo(200);
        assertThat(data(publicList).path("total").asLong()).isEqualTo(1L);
        assertThat(data(publicList).path("list").get(0).has("contentMd")).isFalse();

        MvcResult duplicate =
                mockMvc.perform(
                                post("/solutions")
                                        .header("Authorization", "Bearer " + authorToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(create)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(duplicate)).isEqualTo(200);
        assertThat(data(duplicate).path("id").asLong()).isEqualTo(solutionId);

        MvcResult forbiddenUpdate =
                mockMvc.perform(
                                put("/solutions/" + solutionId)
                                        .header("Authorization", "Bearer " + otherToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title", "伪造编辑",
                                                                "contentMd", "正文",
                                                                "version", 1))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forbiddenUpdate)).isEqualTo(4003);

        MvcResult updated =
                mockMvc.perform(
                                put("/solutions/" + solutionId)
                                        .header("Authorization", "Bearer " + authorToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title", "双指针边界说明",
                                                                "contentMd", "## 更新后的正文",
                                                                "version", 1))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(updated)).isEqualTo(200);
        assertThat(data(updated).path("version").asLong()).isEqualTo(2L);

        String adminToken = loginToken("admin", "admin123");
        MvcResult hidden =
                mockMvc.perform(
                                patch("/admin/solutions/" + solutionId + "/status")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "status", "HIDDEN",
                                                                "version", 2,
                                                                "reason", "示例隐藏"))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(hidden)).isEqualTo(200);
        assertThat(data(hidden).path("status").asText()).isEqualTo("HIDDEN");

        MvcResult hiddenPublic =
                mockMvc.perform(get("/solutions/" + solutionId))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(hiddenPublic)).isEqualTo(4004);

        MvcResult authorEditAfterHidden =
                mockMvc.perform(
                                put("/solutions/" + solutionId)
                                        .header("Authorization", "Bearer " + authorToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title", "仍然隐藏",
                                                                "contentMd", "隐藏后的修改",
                                                                "version", 3))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(authorEditAfterHidden)).isEqualTo(200);
        assertThat(data(authorEditAfterHidden).path("status").asText()).isEqualTo("HIDDEN");

        MvcResult restored =
                mockMvc.perform(
                                patch("/admin/solutions/" + solutionId + "/status")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "status",
                                                                "PUBLISHED",
                                                                "version",
                                                                4))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(restored)).isEqualTo(200);
        assertThat(data(restored).path("status").asText()).isEqualTo("PUBLISHED");

        MvcResult visibleAgain =
                mockMvc.perform(get("/solutions/" + solutionId))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(visibleAgain)).isEqualTo(200);
        assertThat(data(visibleAgain).path("title").asText()).isEqualTo("仍然隐藏");
    }

    @Test
    void teacherCannotUseAdminSolutionEndpoint() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/admin/solutions")
                                        .header(
                                                "Authorization",
                                                "Bearer " + loginToken("teacher", "teacher123")))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(4003);
    }

    private long createPublicQuestion(String token) throws Exception {
        String title = "题解测试_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result =
                mockMvc.perform(
                                post("/questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title", title,
                                                                "description", "题解关联题",
                                                                "difficulty", "EASY"))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(result)).isEqualTo(200);
        return data(result).path("id").asLong();
    }

    private String registerAndLogin(String username) throws Exception {
        MvcResult registered =
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
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(registered)).isEqualTo(200);
        return loginToken(username, "pass123456");
    }
}
