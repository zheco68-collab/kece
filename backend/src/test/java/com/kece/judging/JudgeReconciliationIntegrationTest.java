package com.kece.judging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "kece.judging.persistent-ingress-enabled=true")
class JudgeReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate db;

    private String adminToken;
    private String teacherToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = loginToken("admin", "admin123");
        teacherToken = loginToken("teacher", "teacher123");
    }

    @AfterEach
    void tearDown() {
        db.update(
                "DELETE FROM judge_pool_route WHERE pool IN ('python', 'cpp', 'java', 'mysql', 'arkts')");
    }

    @Test
    void routesEndpointAllowsAdminToQueryAndUpdate() throws Exception {
        // 教师访问被拒
        var forbidden =
                mockMvc.perform(
                                get("/admin/judge-routes")
                                        .header("Authorization", "Bearer " + teacherToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forbidden)).isEqualTo(4003);

        // 管理员查询全部路由，默认全部为 PERSISTENT
        var resp =
                mockMvc.perform(
                                get("/admin/judge-routes")
                                        .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(resp)).isEqualTo(200);
        JsonNode routes = data(resp);
        assertThat(routes.isArray()).isTrue();
        assertThat(routes.size()).isEqualTo(20); // 5 pools * 4 scenes
        for (JsonNode r : routes) {
            assertThat(r.path("dispatchMode").asText()).isEqualTo("PERSISTENT");
        }

        // 更新路由为 PERSISTENT
        var updateResp =
                mockMvc.perform(
                                put("/admin/judge-routes/python/PROBLEM")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"dispatchMode\":\"PERSISTENT\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(updateResp)).isEqualTo(200);
        JsonNode updated = data(updateResp);
        assertThat(updated.path("pool").asText()).isEqualTo("python");
        assertThat(updated.path("scene").asText()).isEqualTo("PROBLEM");
        assertThat(updated.path("dispatchMode").asText()).isEqualTo("PERSISTENT");
        assertThat(updated.path("revision").asLong()).isGreaterThanOrEqualTo(1);

        // 再次更新 PERSISTENT，revision 递增；已退役的 LEGACY 应被拒绝
        long rev1 = updated.path("revision").asLong();
        var nextResp =
                mockMvc.perform(
                                put("/admin/judge-routes/python/PROBLEM")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"dispatchMode\":\"PERSISTENT\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(nextResp)).isEqualTo(200);
        JsonNode next = data(nextResp);
        assertThat(next.path("dispatchMode").asText()).isEqualTo("PERSISTENT");
        assertThat(next.path("revision").asLong()).isEqualTo(rev1 + 1);

        var legacyResp =
                mockMvc.perform(
                                put("/admin/judge-routes/python/PROBLEM")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"dispatchMode\":\"LEGACY\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(legacyResp)).isEqualTo(4001);
    }

    @Test
    void reconciliationDetectsHealthySystemAndCsvExport() throws Exception {
        var resp =
                mockMvc.perform(
                                get("/admin/judgers/reconciliation")
                                        .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(resp)).isEqualTo(200);
        JsonNode report = data(resp);
        assertThat(report.path("healthy").asBoolean()).isTrue();
        assertThat(report.path("totalViolations").asInt()).isEqualTo(0);
        assertThat(report.path("jobBacklog").isArray()).isTrue();
        assertThat(report.path("eventBacklog").isArray()).isTrue();

        // 导出 CSV
        var csvResp =
                mockMvc.perform(
                                get("/admin/judgers/reconciliation?format=csv")
                                        .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                "attachment; filename=\"judge-reconciliation.csv\""))
                        .andReturn();
        String csv = csvResp.getResponse().getContentAsString();
        assertThat(csv).contains("# KECE Judge Reconciliation Report");
        assertThat(csv).contains("Status: HEALTHY");
    }

    @Test
    void reconciliationDetectsInjectedPointerViolation() throws Exception {
        long fakeJobId = 999999999L;
        // 构造孤儿指针
        db.update(
                """
                INSERT INTO submission (user_id, question_id, language, code_content, status, current_judge_job_id, created_at)
                VALUES (1, 1, 'python', 'print(1)', 'PENDING', ?, UTC_TIMESTAMP(6))
                """,
                fakeJobId);
        Long insertedSubId =
                db.queryForObject(
                        "SELECT id FROM submission WHERE current_judge_job_id=?",
                        Long.class,
                        fakeJobId);

        try {
            var resp =
                    mockMvc.perform(
                                    get("/admin/judgers/reconciliation")
                                            .header("Authorization", "Bearer " + adminToken))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(code(resp)).isEqualTo(200);
            JsonNode report = data(resp);
            assertThat(report.path("healthy").asBoolean()).isFalse();
            assertThat(report.path("totalViolations").asInt()).isGreaterThanOrEqualTo(1);

            boolean found = false;
            for (JsonNode v : report.path("violations")) {
                if ("POINTER_MISMATCH_ALGO".equals(v.path("category").asText())
                        && ("sub-" + insertedSubId).equals(v.path("entityId").asText())) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        } finally {
            db.update("DELETE FROM submission WHERE id=?", insertedSubId);
        }
    }
}
