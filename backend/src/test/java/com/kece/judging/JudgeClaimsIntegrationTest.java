package com.kece.judging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.kece.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class JudgeClaimsIntegrationTest extends AbstractIntegrationTest {
    @Autowired private JdbcTemplate db;

    @Test
    void authenticatedLegacyNodeAdoptsSignedIdentityWithoutChangingItsNodeOrHost() throws Exception {
        String name = "legacy-" + UUID.randomUUID();
        String instance = UUID.randomUUID().toString();
        String hostId = UUID.randomUUID().toString();
        String credential = token();
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8)));
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicKey = pair.getPublic().getEncoded();
        String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        db.update("INSERT INTO judge_host_group (name,max_slots) VALUES (?,2)", name);
        long host = db.queryForObject("SELECT id FROM judge_host_group WHERE name=?", Long.class, name);
        db.update("INSERT INTO judge_node (host_group_id,name,pool,runtime_spec,max_slots,credential_hash) VALUES (?,?,'python','python-v1',2,?)",
                host, name, digest);
        long node = db.queryForObject("SELECT id FROM judge_node WHERE name=? AND pool='python'", Long.class, name);
        String boot = UUID.randomUUID().toString().replace("-", "");
        var session = mockMvc.perform(post("/internal/judgers/session")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bootId", boot, "protocolVersion", 1))))
                .andExpect(status().isOk()).andReturn();
        long epoch = objectMapper.readTree(session.getResponse().getContentAsString()).path("sessionEpoch").asLong();
        String control = db.queryForObject("SELECT control_plane_id FROM judge_control_identity WHERE id=1", String.class);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
                Map.of("controlPlaneId", control, "nodeId", Long.toString(node), "sessionEpoch", epoch,
                        "instanceId", instance, "hostId", hostId, "nodeKeyId", fingerprint)));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(("KECE-IDENTITY-MIGRATION-V1\n" + encoded).getBytes(StandardCharsets.US_ASCII));
        var proof = Map.of("publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey),
                "payload", encoded, "signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        String heartbeat = objectMapper.writeValueAsString(Map.of("runtimeSpec", "python-v1", "runtimeVersion", "test-1",
                "localSlots", 2, "healthy", false, "identity", proof));
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/internal/judgers/heartbeat")
                            .header("Authorization", "Bearer " + credential)
                            .header("X-Judge-Session-Epoch", epoch)
                            .contentType(MediaType.APPLICATION_JSON).content(heartbeat))
                    .andExpect(status().isOk());
        }
        assertThat(db.queryForObject("SELECT instance_id FROM judge_node_identity WHERE node_id=?", String.class, node))
                .isEqualTo(instance);
        assertThat(db.queryForObject("SELECT host_group_id FROM judge_host_identity WHERE host_id=?", Long.class, hostId))
                .isEqualTo(host);
        assertThat(db.queryForObject("SELECT host_group_id FROM judge_node WHERE id=?", Long.class, node))
                .isEqualTo(host);
    }

    @Test
    void signedApprovedClaimIsIdempotentAndDoesNotEnableNode() throws Exception {
        String instance = UUID.randomUUID().toString();
        String hostName = "host-" + UUID.randomUUID();
        String name = "api-" + instance;
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicKey = pair.getPublic().getEncoded();
        String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        String control = db.queryForObject(
                "SELECT control_plane_id FROM judge_control_identity WHERE id=1", String.class);
        String capability = hex(MessageDigest.getInstance("SHA-256").digest(
                ("KECE-CAPABILITY-V1\n" + String.join("\n", instance, UUID.randomUUID().toString(),
                        "python", "python-v1", "test-1", "2", fingerprint))
                        .getBytes(StandardCharsets.UTF_8)));

        db.update("INSERT INTO judge_host_group (name,max_slots) VALUES (?,2)", hostName);
        long host = db.queryForObject("SELECT id FROM judge_host_group WHERE name=?", Long.class, hostName);
        db.update("INSERT INTO judge_node (host_group_id,name,pool,runtime_spec,max_slots) VALUES (?,?,'python','python-v1',2)", host, name);
        long node = db.queryForObject("SELECT id FROM judge_node WHERE pool='python' AND name=?", Long.class, name);
        db.update("INSERT INTO judge_node_identity (node_id,instance_id,public_key,key_fingerprint,binding_generation) VALUES (?,?,?,?,1)",
                node, instance, publicKey, fingerprint);
        String key = UUID.randomUUID().toString();
        String fakeHash = "a".repeat(64);
        db.update("INSERT INTO judge_node_connection (actor_id,idempotency_key,request_digest,endpoint_hash,normalized_url,instance_id,active_instance_id,node_id,capability_digest,binding_generation,expected_node_revision,status,next_run_at,created_at,updated_at,expires_at) "
                        + "VALUES (1,?,?,?,?,?,?,?,?,1,1,'WAITING_BIND',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE)",
                key, fakeHash, fakeHash, "http://10.77.0.29:19091", instance, instance, node, capability);
        long connection = db.queryForObject("SELECT id FROM judge_node_connection WHERE idempotency_key=?", Long.class, key);

        String claimId = UUID.randomUUID().toString();
        String credential = token();
        var payload = objectMapper.createObjectNode();
        payload.put("protocolVersion", 1);
        payload.put("controlPlaneId", control);
        payload.put("instanceId", instance);
        payload.put("nodeKeyId", fingerprint);
        payload.put("capabilityDigest", capability);
        payload.put("claimId", claimId);
        String boot = UUID.randomUUID().toString().replace("-", "");
        payload.put("bootId", boot);
        payload.put("issuedAt", System.currentTimeMillis());
        payload.put("nonce", token());
        payload.put("credential", credential);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(payload));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(("KECE-BOOTSTRAP-CLAIM-V1\n" + encoded).getBytes(StandardCharsets.US_ASCII));
        String request = objectMapper.writeValueAsString(Map.of(
                "publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey),
                "payload", encoded,
                "signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())));

        var first = mockMvc.perform(post("/internal/judgers/bootstrap/claim")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn();
        JsonNode confirmed = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(confirmed.path("connectionId").asText()).isEqualTo(Long.toString(connection));
        assertThat(confirmed.path("nodeId").asText()).isEqualTo(Long.toString(node));
        var retry = mockMvc.perform(post("/internal/judgers/bootstrap/claim")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()))
                .isEqualTo(confirmed);
        assertThat(db.queryForObject("SELECT credential_hash FROM judge_node WHERE id=?", String.class, node))
                .isEqualTo(hex(MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8))));
        assertThat(db.queryForObject("SELECT status FROM judge_node_connection WHERE id=?", String.class, connection))
                .isEqualTo("WAITING_HEARTBEAT");
        assertThat(db.queryForObject("SELECT state FROM judge_node WHERE id=?", String.class, node))
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(db.queryForObject("SELECT verified FROM judge_node WHERE id=?", Boolean.class, node))
                .isFalse();

        var session = mockMvc.perform(post("/internal/judgers/session")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bootId", boot, "protocolVersion", 1))))
                .andExpect(status().isOk()).andReturn();
        long epoch = objectMapper.readTree(session.getResponse().getContentAsString())
                .path("sessionEpoch").asLong();
        assertThat(epoch).isPositive();
        var firstHeartbeat = mockMvc.perform(post("/internal/judgers/heartbeat")
                        .header("Authorization", "Bearer " + credential)
                        .header("X-Judge-Session-Epoch", epoch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "runtimeSpec", "python-v1", "runtimeVersion", "test-1",
                                "localSlots", 2, "healthy", true))))
                .andExpect(status().isOk()).andReturn();
        JsonNode heartbeat = objectMapper.readTree(firstHeartbeat.getResponse().getContentAsString());
        String checkId = heartbeat.path("check").path("checkId").asText();
        assertThat(checkId).isNotBlank();
        assertThat(db.queryForObject("SELECT status FROM judge_node_connection WHERE id=?", String.class, connection))
                .isEqualTo("CHECKING");
        mockMvc.perform(post("/internal/judgers/heartbeat")
                        .header("Authorization", "Bearer " + credential)
                        .header("X-Judge-Session-Epoch", epoch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "runtimeSpec", "python-v1", "runtimeVersion", "test-1",
                                "localSlots", 2, "healthy", true,
                                "checkResult", Map.of("checkId", checkId, "passed", false, "detail", "missing image")))))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT status FROM judge_node_connection WHERE id=?", String.class, connection))
                .isEqualTo("FAILED");
        String retryKey = UUID.randomUUID().toString();
        db.update("INSERT INTO judge_node_connection (actor_id,idempotency_key,request_digest,endpoint_hash,normalized_url,instance_id,active_instance_id,node_id,capability_digest,binding_generation,expected_node_revision,credential_digest,status,next_run_at,created_at,updated_at,expires_at) "
                        + "VALUES (1,?,?,?,?,?,?,?,?,1,1,?,'WAITING_HEARTBEAT',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE)",
                retryKey, fakeHash, fakeHash, "http://10.77.0.29:19091", instance, instance, node, capability,
                hex(MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8))));
        long retriedConnection = db.queryForObject("SELECT id FROM judge_node_connection WHERE idempotency_key=?", Long.class, retryKey);
        var repeatedHeartbeat = mockMvc.perform(post("/internal/judgers/heartbeat")
                        .header("Authorization", "Bearer " + credential)
                        .header("X-Judge-Session-Epoch", epoch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "runtimeSpec", "python-v1", "runtimeVersion", "test-1",
                                "localSlots", 2, "healthy", true))))
                .andExpect(status().isOk()).andReturn();
        String retryCheckId = objectMapper.readTree(repeatedHeartbeat.getResponse().getContentAsString())
                .path("check").path("checkId").asText();
        assertThat(retryCheckId).isNotBlank().isNotEqualTo(checkId);
        var checked = mockMvc.perform(post("/internal/judgers/heartbeat")
                        .header("Authorization", "Bearer " + credential)
                        .header("X-Judge-Session-Epoch", epoch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "runtimeSpec", "python-v1", "runtimeVersion", "test-1",
                                "localSlots", 2, "healthy", true,
                                "checkResult", Map.of("checkId", retryCheckId, "passed", true, "detail", "ok")))))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(checked.getResponse().getContentAsString()).path("state").asText())
                .isEqualTo("ENABLED");
        assertThat(db.queryForObject("SELECT status FROM judge_node_connection WHERE id=?", String.class, retriedConnection))
                .isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT verified FROM judge_node WHERE id=?", Boolean.class, node))
                .isTrue();

        db.update("UPDATE judge_node_connection SET status='CANCELLED' WHERE id=?", connection);
        db.update("UPDATE judge_node SET credential_hash=NULL WHERE id=?", node);
        var stale = mockMvc.perform(post("/internal/judgers/bootstrap/claim")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict()).andReturn();
        assertThat(objectMapper.readTree(stale.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("STALE_NODE_CLAIM");
    }

    private static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
