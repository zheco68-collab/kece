package com.kece.judging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import com.kece.judging.control.JudgeConnections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class JudgeConnectionsIntegrationTest extends AbstractIntegrationTest {
    @Autowired private JdbcTemplate db;
    @Autowired private JudgeConnections connections;

    @Test
    void cancellingClaimedConnectionFencesCredentialAndRequiresFreshApproval() throws Exception {
        String name = "cancel-" + UUID.randomUUID();
        String instance = UUID.randomUUID().toString();
        String url = "http://10.77.0.27:19091";
        String digest = "b".repeat(64);
        db.update("INSERT INTO judge_host_group (name,max_slots) VALUES (?,2)", name);
        long host = db.queryForObject("SELECT id FROM judge_host_group WHERE name=?", Long.class, name);
        db.update("INSERT INTO judge_node (host_group_id,name,pool,runtime_spec,max_slots,credential_hash) VALUES (?,?,'python','python-v1',2,?)", host, name, digest);
        long node = db.queryForObject("SELECT id FROM judge_node WHERE name=? AND pool='python'", Long.class, name);
        db.update("INSERT INTO judge_node_identity (node_id,instance_id,public_key,key_fingerprint,binding_generation) VALUES (?,?,?,?,1)",
                node, instance, new byte[] {1}, "c".repeat(64));
        String key = UUID.randomUUID().toString();
        String endpointHash = "d".repeat(64);
        db.update("INSERT INTO judge_node_connection (actor_id,idempotency_key,request_digest,endpoint_hash,normalized_url,instance_id,active_instance_id,node_id,credential_digest,status,next_run_at,created_at,updated_at,expires_at) "
                + "VALUES (1,?,?,?,?,?,?,?,?,'WAITING_HEARTBEAT',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE)",
                key, endpointHash, endpointHash, url, instance, instance, node, digest);
        long id = db.queryForObject("SELECT id FROM judge_node_connection WHERE idempotency_key=?", Long.class, key);
        db.update("INSERT INTO judge_node_endpoint (endpoint_hash,normalized_url,node_id,active_connection_id,updated_at) VALUES (?,?,?,?,UTC_TIMESTAMP(6))",
                endpointHash, url, node, id);
        String admin = loginToken("admin", "admin123");
        mockMvc.perform(post("/admin/judgers/connections/" + id + "/cancel")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT credential_hash FROM judge_node WHERE id=?", String.class, node)).isNull();
        assertThat(db.queryForObject("SELECT state FROM judge_node WHERE id=?", String.class, node)).isEqualTo("DISABLED");
        assertThat(db.queryForObject("SELECT binding_generation FROM judge_node_identity WHERE node_id=?", Long.class, node)).isEqualTo(2L);
        assertThat(db.queryForObject("SELECT revoked_at IS NOT NULL FROM judge_node_identity WHERE node_id=?", Boolean.class, node)).isTrue();
        assertThat(db.queryForObject("SELECT active_connection_id FROM judge_node_endpoint WHERE endpoint_hash=?", Long.class, endpointHash)).isNull();
    }

    @Test
    void oneApiUrlCreatesOnePendingConnectionAndCanBeCancelled() throws Exception {
        String admin = loginToken("admin", "admin123");
        String url = "http://10.77.0.15:19091/" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        JsonNode first = connect(admin, url, key);
        String id = first.path("data").path("connectionId").asText();
        assertThat(first.path("data").path("status").asText()).isEqualTo("REQUESTED");
        assertThat(connect(admin, url, key).path("data").path("connectionId").asText())
                .isEqualTo(id);
        assertThat(
                        connect(admin, url, UUID.randomUUID().toString())
                                .path("data")
                                .path("connectionId")
                                .asText())
                .isEqualTo(id);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM judge_node_connection WHERE id=?",
                                Integer.class,
                                Long.parseLong(id)))
                .isEqualTo(1);

        JsonNode different = connect(admin, "http://10.77.0.16:19091", key);
        assertThat(different.path("code").asInt()).isEqualTo(4009);
        MvcResult cancelled =
                mockMvc.perform(
                                post("/admin/judgers/connections/" + id + "/cancel")
                                        .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(cancelled).path("data").path("status").asText()).isEqualTo("CANCELLED");
        MvcResult found =
                mockMvc.perform(
                                get("/admin/judgers/connections/" + id)
                                        .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(found).path("data").path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void expiredApprovalReleasesEndpointWithoutCreatingNode() throws Exception {
        String admin = loginToken("admin", "admin123");
        String teacher = loginToken("teacher", "teacher123");
        var legacy = mockMvc.perform(post("/admin/judgers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(legacy).path("code").asInt()).isEqualTo(4003);
        String url = "http://10.77.0.18:19091/" + UUID.randomUUID();
        JsonNode forbidden = connect(teacher, url, UUID.randomUUID().toString());
        assertThat(forbidden.path("code").asInt()).isEqualTo(4003);

        String id =
                connect(admin, url, UUID.randomUUID().toString())
                        .path("data")
                        .path("connectionId")
                        .asText();
        db.update(
                "UPDATE judge_node_connection SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                Long.parseLong(id));
        connections.processDue();
        assertThat(db.queryForObject(
                        "SELECT status FROM judge_node_connection WHERE id=?", String.class, Long.parseLong(id)))
                .isEqualTo("EXPIRED");
        assertThat(db.queryForObject(
                        "SELECT active_connection_id FROM judge_node_endpoint WHERE normalized_url=?",
                        Long.class,
                        url))
                .isNull();
        assertThat(db.queryForObject(
                        "SELECT node_id FROM judge_node_connection WHERE id=?", Long.class, Long.parseLong(id)))
                .isNull();
    }

    private JsonNode connect(String token, String url, String key) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/admin/judgers/connections")
                                        .header("Authorization", "Bearer " + token)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        java.util.Map.of("apiUrl", url))))
                        .andExpect(status().isOk())
                        .andReturn();
        return body(result);
    }
}
