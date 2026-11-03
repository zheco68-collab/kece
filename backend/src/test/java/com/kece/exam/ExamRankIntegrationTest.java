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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 考试排名集成测试：OI 总分排序 / ACM 罚时口径。 */
class ExamRankIntegrationTest extends AbstractIntegrationTest {

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

    /** 创建含两道客观题（5 分 + 10 分）的试卷，startTime 设为 1 分钟前（已开考），返回 examId。 */
    private long createObjectiveExam(String token, String mode) throws Exception {
        MvcResult q1 =
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
                                                                "1+1=?",
                                                                "options",
                                                                List.of("A. 1", "B. 2"),
                                                                "answer",
                                                                "B",
                                                                "score",
                                                                5))))
                        .andExpect(status().isOk())
                        .andReturn();
        long q1Id = data(q1).path("id").asLong();
        MvcResult q2 =
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
                                                                "3+3=?",
                                                                "options",
                                                                List.of("A. 5", "B. 6"),
                                                                "answer",
                                                                "B",
                                                                "score",
                                                                10))))
                        .andExpect(status().isOk())
                        .andReturn();
        long q2Id = data(q2).path("id").asLong();
        MvcResult exam =
                mockMvc.perform(
                                post("/exams")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "排名卷_"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8),
                                                                "description",
                                                                "d",
                                                                "durationMinutes",
                                                                60,
                                                                "mode",
                                                                mode,
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
                                                                                q1Id,
                                                                                "score",
                                                                                5),
                                                                        Map.of(
                                                                                "questionType",
                                                                                "OBJECTIVE",
                                                                                "questionId",
                                                                                q2Id,
                                                                                "score",
                                                                                10))))))
                        .andExpect(status().isOk())
                        .andReturn();
        return data(exam).path("id").asLong();
    }

    private long[] sessionQuestionIds(String token, long examId) throws Exception {
        MvcResult enter =
                mockMvc.perform(
                                get("/exams/" + examId + "/enter")
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode questions = data(enter).path("questions");
        long[] ids = new long[questions.size()];
        for (int i = 0; i < questions.size(); i++) {
            ids[i] = questions.get(i).path("id").asLong();
        }
        return ids;
    }

    private void answer(String token, long examId, long eqId, String answer) throws Exception {
        mockMvc.perform(
                        put("/exams/" + examId + "/answers")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("examQuestionId", eqId, "answer", answer))))
                .andExpect(status().isOk());
    }

    @Test
    void oiRanking_shouldSortByTotalScore() throws Exception {
        String teacher = teacherToken();
        long examId = createObjectiveExam(teacher, "OI");
        long[] eqIds = sessionQuestionIds(teacher, examId);
        String a = registerStudent();
        String b = registerStudent();

        // A 全对（5+10=15）；B 只对 1 题（5 分）
        answer(a, examId, eqIds[0], "B");
        answer(a, examId, eqIds[1], "B");
        answer(b, examId, eqIds[0], "B");
        answer(b, examId, eqIds[1], "A");
        mockMvc.perform(post("/exams/" + examId + "/submit").header("Authorization", "Bearer " + a))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(post("/exams/" + examId + "/submit").header("Authorization", "Bearer " + b))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult ranking =
                mockMvc.perform(
                                get("/exams/" + examId + "/ranking")
                                        .header("Authorization", "Bearer " + a))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode list = data(ranking);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("totalScore").asInt()).isEqualTo(15);
        assertThat(list.get(0).path("solved").asInt()).isEqualTo(2);
        assertThat(list.get(0).path("fullScore").asInt()).isEqualTo(15);
        assertThat(list.get(1).path("totalScore").asInt()).isEqualTo(5);
        assertThat(list.get(1).path("solved").asInt()).isEqualTo(1);
    }

    @Test
    void acmRanking_shouldCountSolvedWithoutPenaltyForObjective() throws Exception {
        String teacher = teacherToken();
        long examId = createObjectiveExam(teacher, "ACM");
        long[] eqIds = sessionQuestionIds(teacher, examId);
        String a = registerStudent();
        answer(a, examId, eqIds[0], "B");
        mockMvc.perform(post("/exams/" + examId + "/submit").header("Authorization", "Bearer " + a))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult ranking =
                mockMvc.perform(
                                get("/exams/" + examId + "/ranking")
                                        .header("Authorization", "Bearer " + a))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode list = data(ranking);
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("solved").asInt()).isEqualTo(1);
        // 客观题无罚时累加
        assertThat(list.get(0).path("penaltyMinutes").asLong()).isEqualTo(0);
    }
}
