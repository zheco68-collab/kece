package com.kece.judging.control;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import jakarta.annotation.PreDestroy;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The admin enters only an API URL. Discovery and registration happen outside the request thread.
 */
@Service
@RequiredArgsConstructor
public class JudgeConnections {
    private static final String VIEW_COLUMNS =
            "id,normalized_url,node_id,status,error_code,error_detail,retry_count,created_at,updated_at";
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final JudgeDiscoveryClient discovery;
    @Value("${kece.judging.new-host-slots:2}") private int newHostSlots;
    private final ThreadPoolExecutor probes =
            new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>());

    @PreDestroy
    void stopProbes() {
        probes.shutdownNow();
    }

    public record ConnectionView(
            String connectionId,
            String apiUrl,
            String nodeId,
            String status,
            String errorCode,
            String errorDetail,
            int retryCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    private record NodeBinding(boolean valid, boolean revoked, long generation, long revision, String state, String credential) {}

    public ConnectionView request(String rawUrl, String suppliedKey) {
        Long actorId = AuthContextHolder.currentUserId();
        if (actorId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        String url = discovery.normalize(rawUrl);
        String hash = JudgeTokens.digest(url);
        String key = suppliedKey == null ? UUID.randomUUID().toString() : suppliedKey.strip();
        if (!key.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的幂等键");
        }
        try {
            return transaction.execute(
                    ignored -> {
                        Long sameKey =
                                db.query(
                                        "SELECT id FROM judge_node_connection WHERE actor_id=? AND idempotency_key=?",
                                        r -> r.next() ? r.getLong(1) : null,
                                        actorId,
                                        key);
                        if (sameKey != null) {
                            return sameRequest(sameKey, hash);
                        }
                        db.update(
                                "INSERT IGNORE INTO judge_node_endpoint (endpoint_hash,normalized_url,updated_at) VALUES (?,?,UTC_TIMESTAMP(6))",
                                hash,
                                url);
                        Long active =
                                db.queryForObject(
                                        "SELECT active_connection_id FROM judge_node_endpoint WHERE endpoint_hash=? FOR UPDATE",
                                        Long.class,
                                        hash);
                        if (active != null) {
                            String status =
                                    db.queryForObject(
                                            "SELECT status FROM judge_node_connection WHERE id=?",
                                            String.class,
                                            active);
                        if (List.of("REQUESTED", "DISCOVERING", "WAITING_BIND", "WAITING_HEARTBEAT", "CHECKING")
                                    .contains(status)) {
                                return get(active);
                            }
                        }
                        long id =
                                insert(
                                        "INSERT INTO judge_node_connection (actor_id,idempotency_key,request_digest,endpoint_hash,normalized_url,status,next_run_at,created_at,updated_at,expires_at) "
                                                + "VALUES (?,?,?,?,?,'REQUESTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE)",
                                        actorId,
                                        key,
                                        hash,
                                        hash,
                                        url);
                        db.update(
                                "UPDATE judge_node_endpoint SET active_connection_id=?,updated_at=UTC_TIMESTAMP(6) WHERE endpoint_hash=?",
                                id,
                                hash);
                        return get(id);
                    });
        } catch (DuplicateKeyException collision) {
            Long existing =
                    db.query(
                            "SELECT id FROM judge_node_connection WHERE actor_id=? AND idempotency_key=?",
                            r -> r.next() ? r.getLong(1) : null,
                            actorId,
                            key);
            if (existing != null) {
                return sameRequest(existing, hash);
            }
            throw new BusinessException(ResultCode.CONFLICT, "评测机接入正在处理，请稍后重试");
        }
    }

    private ConnectionView sameRequest(long id, String hash) {
        String existing =
                db.queryForObject(
                        "SELECT request_digest FROM judge_node_connection WHERE id=?",
                        String.class,
                        id);
        if (!hash.equals(existing)) {
            throw new BusinessException(ResultCode.CONFLICT, "幂等键已用于其他地址");
        }
        return get(id);
    }

    public List<ConnectionView> list() {
        return db.query(
                "SELECT " + VIEW_COLUMNS + " FROM judge_node_connection ORDER BY id DESC LIMIT 50",
                JudgeConnections::map);
    }

    public ConnectionView get(long id) {
        List<ConnectionView> found =
                db.query(
                        "SELECT " + VIEW_COLUMNS + " FROM judge_node_connection WHERE id=?",
                        JudgeConnections::map,
                        id);
        if (found.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "接入记录不存在");
        }
        return found.get(0);
    }

    public ConnectionView cancel(long id) {
        return transaction.execute(
                ignored -> {
                    int changed =
                            db.update(
                                    "UPDATE judge_node_connection SET status='CANCELLED',active_instance_id=NULL,runner_id=NULL,runner_lease_until=NULL,finished_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) "
                                            + "WHERE id=? AND status IN ('REQUESTED','DISCOVERING','WAITING_BIND','WAITING_HEARTBEAT','CHECKING')",
                                    id);
                    if (changed != 1) {
                        throw new BusinessException(ResultCode.CONFLICT, "当前接入状态不能取消");
                    }
                    fenceClaimedNode(id);
                    db.update(
                            "UPDATE judge_node_endpoint SET active_connection_id=NULL,updated_at=UTC_TIMESTAMP(6) WHERE active_connection_id=?",
                            id);
                    return get(id);
                });
    }

    @Scheduled(fixedDelay = 2000, scheduler = "judgeConnectionExecutor")
    public void processDue() {
        List<Long> expired =
                db.queryForList(
                        "SELECT id FROM judge_node_connection WHERE status IN ('REQUESTED','DISCOVERING','WAITING_BIND','WAITING_HEARTBEAT','CHECKING') AND expires_at<=UTC_TIMESTAMP(6) ORDER BY id LIMIT 1",
                        Long.class);
        if (!expired.isEmpty()) {
            transaction.executeWithoutResult(
                    ignored -> {
                        long stale = expired.get(0);
                        if (db.update(
                                        "UPDATE judge_node_connection SET status='EXPIRED',active_instance_id=NULL,runner_id=NULL,runner_lease_until=NULL,finished_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status IN ('REQUESTED','DISCOVERING','WAITING_BIND','WAITING_HEARTBEAT','CHECKING') AND expires_at<=UTC_TIMESTAMP(6)",
                                        stale)
                                == 1) {
                            fenceClaimedNode(stale);
                            db.update(
                                    "UPDATE judge_node_endpoint SET active_connection_id=NULL,updated_at=UTC_TIMESTAMP(6) WHERE active_connection_id=?",
                                    stale);
                        }
                    });
            return;
        }
        List<Long> due =
                db.queryForList(
                        "SELECT id FROM judge_node_connection WHERE (status='REQUESTED' AND next_run_at<=UTC_TIMESTAMP(6) AND expires_at>UTC_TIMESTAMP(6)) "
                                + "OR (status='DISCOVERING' AND runner_lease_until<UTC_TIMESTAMP(6)) ORDER BY id LIMIT 1",
                        Long.class);
        if (due.isEmpty()) {
            return;
        }
        long id = due.get(0);
        String runner = UUID.randomUUID().toString();
        boolean claimed =
                Boolean.TRUE.equals(
                        transaction.execute(
                                ignored ->
                                        db.update(
                                                        "UPDATE judge_node_connection SET status='DISCOVERING',runner_id=?,runner_epoch=runner_epoch+1,runner_lease_until=UTC_TIMESTAMP(6)+INTERVAL 15 SECOND,updated_at=UTC_TIMESTAMP(6) "
                                                                + "WHERE id=? AND expires_at>UTC_TIMESTAMP(6) AND ((status='REQUESTED' AND next_run_at<=UTC_TIMESTAMP(6)) OR (status='DISCOVERING' AND runner_lease_until<UTC_TIMESTAMP(6)))",
                                                        runner,
                                                        id)
                                                == 1));
        if (!claimed) {
            return;
        }
        String url =
                db.queryForObject(
                        "SELECT normalized_url FROM judge_node_connection WHERE id=?",
                        String.class,
                        id);
        String controlId =
                db.queryForObject(
                        "SELECT control_plane_id FROM judge_control_identity WHERE id=1",
                        String.class);
        Future<JudgeDiscoveryClient.Discovery> probe = null;
        try {
            probe = probes.submit(() -> discovery.discover(url, controlId));
            var found = probe.get(8, TimeUnit.SECONDS);
            transaction.executeWithoutResult(ignored -> bind(id, runner, found));
        } catch (BusinessException
                | DuplicateKeyException
                | ExecutionException
                | TimeoutException e) {
            fail(id, runner, "DISCOVERY_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(id, runner, "DISCOVERY_INTERRUPTED");
        } catch (RuntimeException e) {
            fail(id, runner, "BIND_FAILED");
        } finally {
            if (probe != null && !probe.isDone()) {
                probe.cancel(true);
            }
        }
    }

    private void fenceClaimedNode(long connectionId) {
        List<String[]> rows = db.query("SELECT node_id,credential_digest FROM judge_node_connection WHERE id=?",
                (r, row) -> new String[] {r.getString(1), r.getString(2)}, connectionId);
        if (rows.isEmpty() || rows.get(0)[1] == null) {
            return;
        }
        long nodeId = Long.parseLong(rows.get(0)[0]);
        int changed = db.update("UPDATE judge_node SET credential_hash=NULL,state='DISABLED',healthy=FALSE,verified=FALSE,revision=revision+1,session_epoch=session_epoch+1 WHERE id=? AND credential_hash=? AND state IN ('PENDING_VERIFICATION','DRAINING','DISABLED')",
                nodeId, rows.get(0)[1]);
        if (changed == 1) {
            db.update("UPDATE judge_node_identity SET revoked_at=UTC_TIMESTAMP(6),binding_generation=binding_generation+1 WHERE node_id=?", nodeId);
            db.update("UPDATE judge_node_check SET state='EXPIRED' WHERE node_id=? AND state IN ('QUEUED','RUNNING')", nodeId);
        }
    }

    private void bind(long id, String runner, JudgeDiscoveryClient.Discovery found) {
        String currentRunner =
                db.query(
                        "SELECT runner_id FROM judge_node_connection WHERE id=? AND status='DISCOVERING' FOR UPDATE",
                        r -> r.next() ? r.getString(1) : null,
                        id);
        if (!runner.equals(currentRunner)) {
            return;
        }
        Long host =
                db.query(
                        "SELECT host_group_id FROM judge_host_identity WHERE host_id=?",
                        r -> r.next() ? r.getLong(1) : null,
                        found.hostId());
        if (host == null) {
            String hostName = "host-" + found.hostId();
            db.update(
                    "INSERT IGNORE INTO judge_host_group (name,max_slots) VALUES (?,?)",
                    hostName,
                    Math.max(1, Math.min(256, newHostSlots)));
            host =
                    db.queryForObject(
                            "SELECT id FROM judge_host_group WHERE name=?", Long.class, hostName);
            db.update(
                    "INSERT IGNORE INTO judge_host_identity (host_id,host_group_id,created_at) VALUES (?,?,UTC_TIMESTAMP(6))",
                    found.hostId(),
                    host);
            host =
                    db.queryForObject(
                            "SELECT host_group_id FROM judge_host_identity WHERE host_id=?",
                            Long.class,
                            found.hostId());
        }
        boolean retiredHost = Boolean.TRUE.equals(db.queryForObject(
                "SELECT retired_at IS NOT NULL FROM judge_host_group WHERE id=? FOR UPDATE", Boolean.class, host));
        Long node =
                db.query(
                        "SELECT node_id FROM judge_node_identity WHERE instance_id=?",
                        r -> r.next() ? r.getLong(1) : null,
                        found.instanceId());
        if (node == null) {
            if (retiredHost) {
                throw new BusinessException(ResultCode.CONFLICT, "已删除分组只能由原节点身份重新接入");
            }
            if (!"UNBOUND".equals(found.bindingState())) {
                throw new BusinessException(ResultCode.CONFLICT, "节点已绑定至其他控制面");
            }
            String name =
                    found.displayName().substring(0, Math.min(63, found.displayName().length()))
                            + "-"
                            + found.instanceId();
            int hostSlots =
                    db.queryForObject(
                            "SELECT max_slots FROM judge_host_group WHERE id=? FOR UPDATE",
                            Integer.class,
                            host);
            db.update(
                    "INSERT IGNORE INTO judge_node (host_group_id,name,pool,runtime_spec,max_slots,base_url) VALUES (?,?,?,?,?,?)",
                    host,
                    name,
                    found.pool(),
                    found.runtimeSpec(),
                    Math.min(found.localSlots(), hostSlots),
                    found.normalizedUrl());
            node =
                    db.queryForObject(
                            "SELECT id FROM judge_node WHERE pool=? AND name=?",
                            Long.class,
                            found.pool(),
                            name);
            db.update(
                    "INSERT IGNORE INTO judge_node_identity (node_id,instance_id,public_key,key_fingerprint,binding_generation) VALUES (?,?,?,?,1)",
                    node,
                    found.instanceId(),
                    found.publicKey(),
                    found.keyFingerprint());
        }
        long nodeId = node;
        long hostId = host;
        List<NodeBinding> identity =
                db.query(
                        "SELECT n.host_group_id,n.pool,n.runtime_spec,i.public_key,i.key_fingerprint,i.revoked_at,i.binding_generation,n.revision,n.state,n.credential_hash FROM judge_node n JOIN judge_node_identity i ON i.node_id=n.id WHERE n.id=? AND i.instance_id=? FOR UPDATE",
                        (r, row) ->
                                new NodeBinding(
                                        r.getLong(1) == hostId
                                                && found.pool().equals(r.getString(2))
                                                && found.runtimeSpec().equals(r.getString(3))
                                                && Arrays.equals(found.publicKey(), r.getBytes(4))
                                                && found.keyFingerprint().equals(r.getString(5)),
                                        r.getTimestamp(6) != null,
                                        r.getLong(7),
                                        r.getLong(8),
                                        r.getString(9),
                                        r.getString(10)),
                        nodeId,
                        found.instanceId());
        if (identity.size() != 1 || !identity.get(0).valid()) {
            throw new BusinessException(ResultCode.CONFLICT, "节点身份或宿主机不一致");
        }
        NodeBinding binding = identity.get(0);
        long expectedRevision = binding.revision();
        if (retiredHost) {
            // Reopen the archived host only after the original node key has been verified.
            db.update("UPDATE judge_host_group SET retired_at=NULL,revision=revision+1 WHERE id=?", hostId);
        }
        if (Boolean.TRUE.equals(db.queryForObject(
                "SELECT retired_at IS NOT NULL FROM judge_node WHERE id=?", Boolean.class, nodeId))) {
            if (!binding.revoked() || binding.credential() != null || !"DISABLED".equals(binding.state())
                    || !"UNBOUND".equals(found.bindingState())) {
                throw new BusinessException(ResultCode.CONFLICT, "已删除节点尚未准备好重新接入");
            }
            // Identity and public key were verified above; preserve the old node ID and audit history.
            db.update("UPDATE judge_node SET retired_at=NULL,revision=revision+1 WHERE id=?", nodeId);
            expectedRevision++;
        }
        if (binding.revoked()) {
            if (binding.credential() != null || !"DISABLED".equals(binding.state())
                    || !"UNBOUND".equals(found.bindingState())) {
                throw new BusinessException(ResultCode.CONFLICT, "已撤销节点尚未准备好重新接入");
            }
            db.update("UPDATE judge_node_identity SET revoked_at=NULL WHERE node_id=?", nodeId);
            db.update("UPDATE judge_node SET state='PENDING_VERIFICATION',revision=revision+1 WHERE id=?", nodeId);
            expectedRevision++;
        }
        if (binding.credential() != null && !"BOUND".equals(found.bindingState())) {
            throw new BusinessException(ResultCode.CONFLICT, "节点报告的绑定状态与现有凭据不一致");
        }
        if (binding.credential() == null && !"UNBOUND".equals(found.bindingState())) {
            throw new BusinessException(ResultCode.CONFLICT, "节点缺少凭据，不能复用绑定");
        }
        String endpointHash = JudgeTokens.digest(found.normalizedUrl());
        Long endpointNode =
                db.queryForObject(
                        "SELECT node_id FROM judge_node_endpoint WHERE endpoint_hash=? FOR UPDATE",
                        Long.class,
                        endpointHash);
        if (endpointNode != null && endpointNode != nodeId) {
            throw new BusinessException(ResultCode.CONFLICT, "该地址已属于其他节点");
        }
        if (db.update(
                        "UPDATE judge_node_endpoint SET node_id=?,updated_at=UTC_TIMESTAMP(6) WHERE endpoint_hash=? AND active_connection_id=?",
                        nodeId,
                        endpointHash,
                        id)
                != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "接入任务已取消或地址已变化");
        }
        db.update("UPDATE judge_node SET base_url=? WHERE id=?", found.normalizedUrl(), nodeId);
        String target =
                binding.credential() == null
                        ? "WAITING_BIND"
                        : "PENDING_VERIFICATION".equals(binding.state())
                                ? "WAITING_HEARTBEAT"
                                : "SUCCEEDED";
        if (db.update(
                        "UPDATE judge_node_connection SET instance_id=?,active_instance_id=?,node_id=?,capability_digest=?,binding_generation=?,expected_node_revision=?,credential_digest=?,status=?,runner_id=NULL,runner_lease_until=NULL,finished_at=IF(?='SUCCEEDED',UTC_TIMESTAMP(6),NULL),updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='DISCOVERING' AND runner_id=?",
                        found.instanceId(),
                        "SUCCEEDED".equals(target) ? null : found.instanceId(),
                        nodeId,
                        found.capabilityDigest(),
                        binding.generation(),
                        expectedRevision,
                        binding.credential(),
                        target,
                        target,
                        id,
                        runner)
                != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "接入任务已变化");
        }
        if ("SUCCEEDED".equals(target)) {
            db.update(
                    "UPDATE judge_node_endpoint SET active_connection_id=NULL,updated_at=UTC_TIMESTAMP(6) WHERE endpoint_hash=? AND active_connection_id=?",
                    endpointHash,
                    id);
        }
    }

    private void fail(long id, String runner, String code) {
        transaction.executeWithoutResult(
                ignored -> {
                    db.update(
                            "UPDATE judge_node_connection SET status=IF(retry_count>=2,'FAILED','REQUESTED'),finished_at=IF(retry_count>=2,UTC_TIMESTAMP(6),NULL),retry_count=retry_count+1,error_code=?,error_detail='无法验证评测机接口或节点身份',next_run_at=UTC_TIMESTAMP(6)+INTERVAL 5 SECOND,runner_id=NULL,runner_lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='DISCOVERING' AND runner_id=?",
                            code,
                            id,
                            runner);
                    db.update(
                            "UPDATE judge_node_endpoint SET active_connection_id=NULL,updated_at=UTC_TIMESTAMP(6) WHERE active_connection_id=? AND EXISTS (SELECT 1 FROM judge_node_connection WHERE id=? AND status='FAILED')",
                            id,
                            id);
                });
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) {
                        statement.setObject(i + 1, args[i]);
                    }
                    return statement;
                },
                key);
        return key.getKey().longValue();
    }

    private static ConnectionView map(java.sql.ResultSet r, int row) throws java.sql.SQLException {
        return new ConnectionView(
                r.getString("id"),
                r.getString("normalized_url"),
                r.getString("node_id"),
                r.getString("status"),
                r.getString("error_code"),
                r.getString("error_detail"),
                r.getInt("retry_count"),
                r.getTimestamp("created_at").toLocalDateTime(),
                r.getTimestamp("updated_at").toLocalDateTime());
    }
}
