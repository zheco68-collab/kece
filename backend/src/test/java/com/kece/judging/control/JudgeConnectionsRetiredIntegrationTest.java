package com.kece.judging.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kece.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class JudgeConnectionsRetiredIntegrationTest extends AbstractIntegrationTest {
    @Autowired private JdbcTemplate db;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JudgeConnections connections;

    @Test
    void deletedNodeAndHostCanReconnectFromTheSameSignedIdentity() {
        String instanceId = UUID.randomUUID().toString();
        String hostId = UUID.randomUUID().toString();
        String name = "retired-" + instanceId;
        String url = "http://10.77.0.27:19102";
        String endpointHash = JudgeTokens.digest(url);
        String runner = UUID.randomUUID().toString();
        byte[] publicKey = {1, 2, 3};
        String fingerprint = "a".repeat(64);

        db.update("INSERT INTO judge_host_group (name,max_slots,retired_at) VALUES (?,2,UTC_TIMESTAMP(6))", name);
        long host = db.queryForObject("SELECT id FROM judge_host_group WHERE name=?", Long.class, name);
        db.update("INSERT INTO judge_host_identity (host_id,host_group_id,created_at) VALUES (?,?,UTC_TIMESTAMP(6))", hostId, host);
        db.update("INSERT INTO judge_node (host_group_id,name,pool,runtime_spec,max_slots,state,retired_at) "
                        + "VALUES (?,?,'java','java-v1',2,'DISABLED',UTC_TIMESTAMP(6))", host, name);
        long node = db.queryForObject("SELECT id FROM judge_node WHERE name=? AND pool='java'", Long.class, name);
        db.update("INSERT INTO judge_node_identity (node_id,instance_id,public_key,key_fingerprint,binding_generation,revoked_at) "
                        + "VALUES (?,?,?,?,2,UTC_TIMESTAMP(6))", node, instanceId, publicKey, fingerprint);
        db.update("INSERT INTO judge_node_connection (actor_id,idempotency_key,request_digest,endpoint_hash,normalized_url,status,runner_id,next_run_at,created_at,updated_at,expires_at) "
                        + "VALUES (1,?,?,?,?, 'DISCOVERING',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE)",
                UUID.randomUUID().toString(), endpointHash, endpointHash, url, runner);
        long connection = db.queryForObject("SELECT id FROM judge_node_connection WHERE runner_id=?", Long.class, runner);
        db.update("INSERT INTO judge_node_endpoint (endpoint_hash,normalized_url,active_connection_id,updated_at) "
                        + "VALUES (?,?,?,UTC_TIMESTAMP(6))", endpointHash, url, connection);

        var discovery = new JudgeDiscoveryClient.Discovery(
                url, instanceId, hostId, fingerprint, publicKey, "b".repeat(32), "java",
                "java-v1", "17", name, 2, "c".repeat(64), "UNBOUND", "CHECKING");
        var differentKey = new JudgeDiscoveryClient.Discovery(
                url, instanceId, hostId, fingerprint, new byte[] {4, 5, 6}, "b".repeat(32), "java",
                "java-v1", "17", name, 2, "c".repeat(64), "UNBOUND", "CHECKING");
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                ReflectionTestUtils.invokeMethod(connections, "bind", connection, runner, differentKey)))
                .hasMessageContaining("节点身份或宿主机不一致");
        assertThat(db.queryForObject("SELECT retired_at FROM judge_host_group WHERE id=?", java.sql.Timestamp.class, host)).isNotNull();
        assertThat(db.queryForObject("SELECT retired_at FROM judge_node WHERE id=?", java.sql.Timestamp.class, node)).isNotNull();

        transaction.executeWithoutResult(ignored ->
                ReflectionTestUtils.invokeMethod(connections, "bind", connection, runner, discovery));

        assertThat(db.queryForObject("SELECT retired_at FROM judge_host_group WHERE id=?", java.sql.Timestamp.class, host)).isNull();
        assertThat(db.queryForObject("SELECT retired_at FROM judge_node WHERE id=?", java.sql.Timestamp.class, node)).isNull();
        assertThat(db.queryForObject("SELECT state FROM judge_node WHERE id=?", String.class, node)).isEqualTo("PENDING_VERIFICATION");
        assertThat(db.queryForObject("SELECT status FROM judge_node_connection WHERE id=?", String.class, connection)).isEqualTo("WAITING_BIND");
        assertThat(db.queryForObject("SELECT node_id FROM judge_node_connection WHERE id=?", Long.class, connection)).isEqualTo(node);
    }
}
