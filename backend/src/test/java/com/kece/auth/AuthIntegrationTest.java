package com.kece.auth;

import static org.assertj.core.api.Assertions.assertThat;
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

/** 认证接口集成测试。 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    private String uniqueUsername() {
        return "user_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void registerLoginMeRefresh_shouldSucceed() throws Exception {
        String username = uniqueUsername();
        String password = "pass123456";

        // 注册（自动登录返回 token）
        MvcResult register =
                mockMvc.perform(
                                post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                password,
                                                                "email",
                                                                username + "@example.com"))))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode registerData = data(register);
        assertThat(code(register)).isEqualTo(200);
        String token = registerData.path("token").asText();
        String refreshToken = registerData.path("refreshToken").asText();
        assertThat(registerData.path("user").path("roles").get(0).asText()).isEqualTo("USER");

        // 登录
        String loginToken = loginToken(username, password);
        assertThat(loginToken).isNotBlank();

        // 当前用户信息
        MvcResult me =
                mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(data(me).path("username").asText()).isEqualTo(username);

        // 刷新令牌
        MvcResult refresh =
                mockMvc.perform(
                                post("/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of("refreshToken", refreshToken))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(refresh)).isEqualTo(200);
        assertThat(data(refresh).path("token").asText()).isNotBlank();
    }

    @Test
    void registerDuplicateUsername_shouldReturnParamError() throws Exception {
        String username = uniqueUsername();
        Map<String, String> body =
                Map.of(
                        "username",
                        username,
                        "email",
                        username + "@example.com",
                        "password",
                        "pass123456");
        mockMvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second =
                mockMvc.perform(
                                post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(second)).isEqualTo(4001);
    }

    @Test
    void loginWrongPassword_shouldReturnUnauthorized() throws Exception {
        String username = uniqueUsername();
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
        MvcResult login =
                mockMvc.perform(
                                post("/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                "wrongpass"))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(login)).isEqualTo(4002);
    }

    @Test
    void meWithoutToken_shouldReturnUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/me")).andExpect(status().isOk()).andReturn();
        assertThat(code(result)).isEqualTo(4002);
    }
}
