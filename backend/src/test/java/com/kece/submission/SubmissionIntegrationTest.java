package com.kece.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.entity.Submission;
import com.kece.submission.listener.JudgeResultBlockingListener;
import com.kece.submission.mapper.SubmissionMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 提交/轮询接口集成测试：验证状态缓存、队列与结果回写。 */
class SubmissionIntegrationTest extends AbstractIntegrationTest {

    private static final String STATUS_KEY = "sub:status:";
    private static final String RESULTS_KEY = "judge:results";

    @Autowired private SubmissionMapper submissionMapper;

    @Autowired private StringRedisTemplate redis;

    @Autowired private JudgeResultBlockingListener resultListener;

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
    void submitThenPoll_shouldReflectRedisStateAndPersistResult() throws Exception {
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

        // 轮询应命中 Redis，仍为 PENDING
        MvcResult poll =
                mockMvc.perform(
                                get("/submissions/" + submissionId)
                                        .header("Authorization", "Bearer " + userToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(data(poll).path("status").asText()).isEqualTo("PENDING");

        // 模拟判题机写回终态结果
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
        redis.opsForValue()
                .set(STATUS_KEY + submissionId, objectMapper.writeValueAsString(terminal));
        redis.opsForList()
                .rightPush(
                        RESULTS_KEY,
                        submissionId + "\n" + objectMapper.writeValueAsString(terminal));

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

        // 后台监听器异步将结果持久化到 MySQL。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        Submission persisted;
        do {
            persisted = submissionMapper.findById(submissionId);
            if ("ACCEPTED".equals(persisted.getStatus())) {
                break;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(persisted.getStatus()).isEqualTo("ACCEPTED");
        assertThat(persisted.getResultJson()).isNotBlank();
    }

    @Test
    void redisRetainsFailedClaimAndRedeliversAfterLeaseExpiry() {
        String key = "test:judge:results:" + UUID.randomUUID();
        String pending = key + ":pending";
        String payload = "123\n{}";
        try {
            redis.opsForList().rightPush(key, payload);
            assertThatThrownBy(
                            () ->
                                    resultListener.processNext(
                                            key,
                                            (id, result) -> {
                                                throw new IllegalStateException(
                                                        "transient persistence error");
                                            }))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(redis.opsForList().size(key)).isZero();
            var claims = redis.opsForZSet().range(pending, 0, -1);
            assertThat(claims).hasSize(1);
            redis.opsForZSet().add(pending, claims.iterator().next(), 0);

            assertThat(
                            resultListener.processNext(
                                    key,
                                    (id, result) -> {
                                        assertThat(id).isEqualTo(123L);
                                        assertThat(result).isEqualTo("{}");
                                    }))
                    .isTrue();
            assertThat(redis.opsForZSet().size(pending)).isZero();
        } finally {
            redis.delete(List.of(key, pending, key + ":failed"));
        }
    }
}
