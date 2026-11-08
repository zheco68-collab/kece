package com.kece.judging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.AbstractIntegrationTest;
import com.kece.judging.control.JudgeScheduler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "kece.judging.persistent-ingress-enabled=true")
class JudgeControlIntegrationTest extends AbstractIntegrationTest {
    private static final String INTERNAL = "/internal/judgers";
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate db;
    @Autowired private JudgeScheduler scheduler;

    private record Node(String id, String token, long epoch, String pool) {}

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        db.update("UPDATE judge_job SET state = 'CANCELLED' WHERE state IN ('QUEUED', 'RUNNING')");
    }

    @Test
    void leasesFenceRacingNodesAndRespectTheHostBudget() throws Exception {
        String admin = loginToken("admin", "admin123");
        String teacher = loginToken("teacher", "teacher123");
        mockMvc.perform(
                        post(INTERNAL + "/jobs/claim")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"slots\":1}"))
                .andExpect(status().isUnauthorized());
        var forbidden =
                mockMvc.perform(get("/admin/judgers").header("Authorization", "Bearer " + teacher))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forbidden)).isEqualTo(4003);

        String firstHost = host(admin);
        Node a = ready(admin, firstHost, "python");
        Node b = ready(admin, firstHost, "java");
        Node c = ready(admin, host(admin), "python");
        db.update(
                """
                INSERT INTO judge_pool_route (pool,scene,dispatch_mode)
                VALUES ('python','PROBLEM','PERSISTENT'),('java','PROBLEM','PERSISTENT')
                """);
        try {
            long first = submit(teacher, "python");
            var barrier = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            JsonNode fromA;
            JsonNode fromC;
            try {
                var left =
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return claim(a);
                                });
                var right =
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return claim(c);
                                });
                barrier.countDown();
                fromA = left.get(10, TimeUnit.SECONDS);
                fromC = right.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertThat(fromA.size() + fromC.size()).isEqualTo(1);
            Node winner = fromA.isEmpty() ? c : a;
            Node loser = fromA.isEmpty() ? a : c;
            JsonNode job = fromA.isEmpty() ? fromC.get(0) : fromA.get(0);
            String id = job.path("jobId").asText();
            long epoch = job.path("leaseEpoch").asLong();
            var verdict = accepted(job);
            machine(loser, "/jobs/" + id + "/result", verdict, 409);
            var renewed =
                    machine(
                            winner,
                            "/jobs/renew",
                            Map.of(
                                    "jobs",
                                    List.of(
                                            Map.of(
                                                    "jobId",
                                                    id,
                                                    "leaseEpoch",
                                                    epoch,
                                                    "sequence",
                                                    1,
                                                    "phase",
                                                    "JUDGING",
                                                    "finishedCases",
                                                    1))),
                            200);
            assertThat(renewed.path("jobs").get(0).path("status").asText()).isEqualTo("RENEWED");
            assertThat(
                            machine(winner, "/jobs/" + id + "/result", verdict, 200)
                                    .path("status")
                                    .asText())
                    .isEqualTo("COMMITTED");
            assertThat(
                            machine(winner, "/jobs/" + id + "/result", verdict, 200)
                                    .path("status")
                                    .asText())
                    .isEqualTo("ALREADY_COMMITTED");
            assertThat(
                            db.queryForObject(
                                    "SELECT status FROM submission WHERE id=?",
                                    String.class,
                                    first))
                    .isEqualTo("ACCEPTED");
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM judge_settlement_event WHERE job_id=?",
                                    Integer.class,
                                    Long.parseLong(id)))
                    .isEqualTo(1);

            submit(teacher, "python");
            submit(teacher, "java");
            JsonNode held = claim(a).get(0);
            assertThat(claim(b)).isEmpty();
            long heldId = held.path("jobId").asLong();
            db.update("UPDATE judge_job SET lease_until=UTC_TIMESTAMP(6) WHERE id=?", heldId);
            var recovery = Executors.newFixedThreadPool(2);
            try {
                var left = recovery.submit(() -> scheduler.reap(heldId));
                var right = recovery.submit(() -> scheduler.reap(heldId));
                left.get(10, TimeUnit.SECONDS);
                right.get(10, TimeUnit.SECONDS);
            } finally {
                recovery.shutdownNow();
            }
            JsonNode java = claim(b).get(0);
            JsonNode retried = claim(c).get(0);
            assertThat(retried.path("jobId").asLong()).isEqualTo(heldId);
            assertThat(retried.path("leaseEpoch").asLong())
                    .isGreaterThan(held.path("leaseEpoch").asLong());
            machine(a, "/jobs/" + heldId + "/result", accepted(held), 409);
            machine(c, "/jobs/" + heldId + "/result", accepted(retried), 200);
            machine(b, "/jobs/" + java.path("jobId").asText() + "/result", accepted(java), 200);

            MvcResult waiting =
                    mockMvc.perform(
                                    post(INTERNAL + "/jobs/claim")
                                            .header("Authorization", "Bearer " + a.token())
                                            .header("X-Judge-Session-Epoch", a.epoch())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"slots\":1,\"waitMs\":5000}"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            machine(a, "/jobs/claim", Map.of("slots", 1), 429);
            submit(teacher, "python");
            var async =
                    mockMvc.perform(asyncDispatch(waiting)).andExpect(status().isOk()).andReturn();
            JsonNode awakened =
                    json.readTree(async.getResponse().getContentAsByteArray()).path("jobs").get(0);
            machine(
                    a,
                    "/jobs/" + awakened.path("jobId").asText() + "/result",
                    accepted(awakened),
                    200);
        } finally {
            db.update(
                    "DELETE FROM judge_pool_route WHERE pool IN ('python','java') AND scene='PROBLEM'");
        }
    }

    private String host(String admin) throws Exception {
        return adminPost(admin, "/admin/judge-host-groups", Map.of("name", random(), "maxSlots", 1))
                .path("id")
                .asText();
    }

    private Node ready(String admin, String host, String pool) throws Exception {
        JsonNode enrollment =
                adminPost(
                        admin,
                        "/admin/judgers",
                        Map.of("name", random(), "pool", pool, "hostGroupId", host, "maxSlots", 1));
        var join =
                Map.of(
                        "enrollmentCode",
                        enrollment.path("enrollmentCode").asText(),
                        "protocolVersion",
                        1,
                        "bootId",
                        random());
        var joined =
                mockMvc.perform(
                                post(INTERNAL + "/join")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(join)))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode data = json.readTree(joined.getResponse().getContentAsByteArray());
        mockMvc.perform(
                        post(INTERNAL + "/join")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(join)))
                .andExpect(status().isUnauthorized());
        Node n =
                new Node(
                        data.path("nodeId").asText(),
                        data.path("credential").asText(),
                        data.path("sessionEpoch").asLong(),
                        pool);
        assertThat(
                        machine(
                                        n,
                                        "/session",
                                        Map.of("protocolVersion", 1, "bootId", join.get("bootId")),
                                        200)
                                .path("sessionEpoch")
                                .asLong())
                .isEqualTo(n.epoch());
        var healthy =
                Map.of(
                        "runtimeSpec",
                        pool + "-v1",
                        "runtimeVersion",
                        "fixture-1",
                        "localSlots",
                        1,
                        "healthy",
                        true);
        machine(n, "/heartbeat", healthy, 200);
        String path = "/admin/judgers/" + n.id();
        String check = adminPost(admin, path + "/checks", Map.of()).path("checkId").asText();
        assertThat(machine(n, "/heartbeat", healthy, 200).path("check").path("checkId").asText())
                .isEqualTo(check);
        var report = new java.util.HashMap<String, Object>(healthy);
        report.put("checkResult", Map.of("checkId", check, "passed", true));
        machine(n, "/heartbeat", report, 200);
        var updated =
                mockMvc.perform(
                                patch(path)
                                        .header("Authorization", "Bearer " + admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"revision\":1,\"state\":\"ENABLED\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(updated)).isEqualTo(200);
        return n;
    }

    private long submit(String teacher, String pool) throws Exception {
        long question =
                adminPost(
                                teacher,
                                "/questions",
                                Map.of(
                                        "title",
                                        random(),
                                        "description",
                                        "judge control fixture",
                                        "difficulty",
                                        "EASY",
                                        "languages",
                                        pool))
                        .path("id")
                        .asLong();
        adminPost(
                teacher,
                "/questions/" + question + "/test-cases",
                Map.of("input", "", "expectedOutput", "5", "isSample", 1));
        return adminPost(
                        teacher,
                        "/submissions",
                        Map.of(
                                "questionId",
                                question,
                                "language",
                                pool,
                                "codeContent",
                                Map.of("answer", "fixture")))
                .path("submissionId")
                .asLong();
    }

    @Test
    void clusterSummaryReflectsPoolStateAndAdminEndpointsWork() throws Exception {
        String admin = loginToken("admin", "admin123");
        String teacher = loginToken("teacher", "teacher123");

        var forbidden =
                mockMvc.perform(get("/admin/judgers").header("Authorization", "Bearer " + teacher))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(forbidden)).isEqualTo(4003);

        var summaryResp =
                mockMvc.perform(get("/system/judgers/summary"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(summaryResp)).isEqualTo(200);
        JsonNode summary = data(summaryResp);
        assertThat(summary.path("totalPools").asInt()).isEqualTo(5);
        assertThat(summary.path("pools").isArray()).isTrue();
        assertThat(summary.path("pools").size()).isEqualTo(5);
        assertThat(summary.path("freshness").asText()).isEqualTo("FRESH");

        String hostId =
                adminPost(
                                admin,
                                "/admin/judge-host-groups",
                                Map.of("name", random(), "maxSlots", 4))
                        .path("id")
                        .asText();
        var regResp =
                adminPost(
                        admin,
                        "/admin/judgers",
                        Map.of(
                                "name",
                                "summary-test-py-" + random().substring(0, 8),
                                "pool",
                                "python",
                                "hostGroupId",
                                hostId,
                                "maxSlots",
                                2));
        String nodeId = regResp.path("nodeId").asText();
        assertThat(nodeId).isNotEmpty();

        var detailResp =
                mockMvc.perform(
                                get("/admin/judgers/" + nodeId)
                                        .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(detailResp)).isEqualTo(200);
        JsonNode detail = data(detailResp);
        assertThat(detail.path("node").path("maxSlots").asInt()).isEqualTo(2);
        assertThat(detail.path("node").path("state").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(detail.path("checks").isArray()).isTrue();
        assertThat(detail.path("audit").isArray()).isTrue();
        assertThat(detail.path("attempts").isArray()).isTrue();
    }

    private JsonNode claim(Node n) throws Exception {
        return machine(n, "/jobs/claim", Map.of("slots", 1), 200).path("jobs");
    }

    private static Map<String, Object> accepted(JsonNode job) {
        return Map.of(
                "leaseEpoch",
                job.path("leaseEpoch").asLong(),
                "status",
                "ACCEPTED",
                "result",
                Map.of(
                        "score",
                        100,
                        "testCasesReport",
                        List.of(
                                Map.of(
                                        "caseId",
                                        job.path("manifest")
                                                .path("cases")
                                                .get(0)
                                                .path("caseId")
                                                .asLong(),
                                        "status",
                                        "ACCEPTED"))));
    }

    private JsonNode adminPost(String token, String path, Object body) throws Exception {
        var response =
                mockMvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(code(response)).isEqualTo(200);
        return data(response);
    }

    private JsonNode machine(Node n, String path, Object body, int expected) throws Exception {
        var response =
                mockMvc.perform(
                                post(INTERNAL + path)
                                        .header("Authorization", "Bearer " + n.token())
                                        .header("X-Judge-Session-Epoch", n.epoch())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().is(expected))
                        .andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private static String random() {
        return UUID.randomUUID().toString();
    }
}
