package com.kece.exam;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 交卷评测集成测试：客观题同步判分（快照折算/漏选半分）、代码题入队、交卷幂等。 */
class ExamSubmissionIntegrationTest extends AbstractIntegrationTest {

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

    /** 组一份含客观题(SINGLE,5分)+代码题(20分)的试卷，startTime 设为 1 分钟前（已开考）。 */
    private long createExamWithObjectiveAndCode(String token) throws Exception {
        MvcResult obj =
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
        long objId = data(obj).path("id").asLong();
        MvcResult code =
                mockMvc.perform(
                                post("/questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "考试代码题_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                "description",
                                                                "## 题目描述",
                                                                "difficulty",
                                                                "EASY"))))
                        .andExpect(status().isOk())
                        .andReturn();
        long codeQid = data(code).path("id").asLong();
        mockMvc.perform(
                        post("/questions/" + codeQid + "/test-cases")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "input",
                                                        "1",
                                                        "expectedOutput",
                                                        "1",
                                                        "isSample",
                                                        1))))
                .andExpect(status().isOk());
        MvcResult exam =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "交卷评测卷_"
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
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void submitShouldGradeObjectiveAndQueueCode() throws Exception {
        String teacher = teacherToken();
        long examId = createExamWithObjectiveAndCode(teacher);
        String student = registerStudent();

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
                        objectMapper.writeValueAsString(
                                Map.of("main.cpp", "int main(){return 0;}")),
                        "language",
                        "cpp"));

        var start = new CountDownLatch(1);
        Callable<MvcResult> handIn =
                () -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("hand-in test did not start");
                    }
                    return mockMvc.perform(
                                    post("/exams/" + examId + "/submit")
                                            .header("Authorization", "Bearer " + student)
                                            .header("Idempotency-Key", "test-hand-in"))
                            .andExpect(status().isOk())
                            .andReturn();
                };
        var executor = Executors.newFixedThreadPool(2);
        MvcResult submit;
        try {
            var first = executor.submit(handIn);
            var second = executor.submit(handIn);
            start.countDown();
            submit = first.get(10, TimeUnit.SECONDS);
            assertThat(data(second.get(10, TimeUnit.SECONDS))).isEqualTo(data(submit));
        } finally {
            executor.shutdownNow();
        }
        assertThat(code(submit)).isEqualTo(200);
        assertThat(data(submit).path("graded").asInt()).isEqualTo(1);
        assertThat(data(submit).path("queued").asInt()).isEqualTo(1);

        // 客观题已同步判分：ACCEPTED + 快照 5 分
        MvcResult results =
                mockMvc.perform(
                                get("/exams/" + examId + "/results")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode list = data(results);
        assertThat(list.size()).isEqualTo(2);
        for (JsonNode row : list) {
            if (row.path("questionType").asText().equals("OBJECTIVE")) {
                assertThat(row.path("status").asText()).isEqualTo("ACCEPTED");
            } else {
                // 判题机未启动，代码题停留在 PENDING
                assertThat(row.path("status").asText()).isEqualTo("PENDING");
            }
        }

        // 重复交卷被拒绝
        MvcResult again =
                mockMvc.perform(
                                post("/exams/" + examId + "/submit")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(again)).isEqualTo(4001);
        assertThat(body(again).path("message").asText()).isEqualTo("该考试已交卷，不能重复提交");
    }

    @Test
    void multipleChoiceMissingSelection_shouldHalfScoreBySnapshot() throws Exception {
        String token = teacherToken();
        MvcResult obj =
                mockMvc.perform(
                                post("/objective-questions")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "type",
                                                                "MULTIPLE",
                                                                "content",
                                                                "以下哪些是编程语言？（多选）",
                                                                "options",
                                                                List.of(
                                                                        "A. Java",
                                                                        "B. Python",
                                                                        "C. 汉语"),
                                                                "answer",
                                                                "A,B",
                                                                "score",
                                                                10))))
                        .andExpect(status().isOk())
                        .andReturn();
        long objId = data(obj).path("id").asLong();
        MvcResult exam =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "漏选卷_"
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
                                                                                10))))))
                        .andExpect(status().isOk())
                        .andReturn();
        long examId = data(exam).path("id").asLong();

        String student = registerStudent();
        long eqId = examQuestionId(student, examId, "OBJECTIVE");
        saveAnswer(student, examId, eqId, Map.of("examQuestionId", eqId, "answer", "A"));

        mockMvc.perform(
                        post("/exams/" + examId + "/submit")
                                .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult results =
                mockMvc.perform(
                                get("/exams/" + examId + "/results")
                                        .header("Authorization", "Bearer " + student))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode row = data(results).get(0);
        // 漏选：WRONG_ANSWER，快照 10 分取半（向上取整 5 分）
        assertThat(row.path("status").asText()).isEqualTo("WRONG_ANSWER");
        assertThat(row.path("score").asInt()).isEqualTo(5);
        assertThat(row.path("fullScore").asInt()).isEqualTo(10);
    }
}
