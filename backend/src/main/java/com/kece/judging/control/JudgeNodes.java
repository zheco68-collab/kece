package com.kece.judging.control;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JudgeNodes {
    private static final Set<String> POOLS = Set.of("cpp", "java", "python", "mysql", "arkts");
    private static final String NODE_SELECT =
            """
            SELECT n.*, COALESCE(heartbeat_at > UTC_TIMESTAMP(6) - INTERVAL 30 SECOND, 0) AS fresh
            FROM judge_node n
            """;
    private final JdbcTemplate db;
    private final JudgeEndpointProbe endpointProbe;

    record Access(long nodeId, String hash, long epoch) {}

    record Node(
            long id,
            long host,
            String name,
            String pool,
            String runtime,
            String state,
            int maxSlots,
            int localSlots,
            long revision,
            long epoch,
            String boot,
            String hash,
            boolean healthy,
            boolean verified,
            boolean fresh,
            String runtimeVersion,
            String baseUrl,
            LocalDateTime heartbeatAt) {}

    public record HostView(String id, String name, int maxSlots, long revision) {}

    public record Enrollment(String nodeId, String enrollmentCode, int expiresInSeconds) {}

    public record Session(
            String nodeId,
            long sessionEpoch,
            int protocolVersion,
            String pool,
            String runtimeSpec) {}

    public record Joined(
            String nodeId,
            String credential,
            long sessionEpoch,
            int protocolVersion,
            String pool,
            String runtimeSpec) {}

    public record Heartbeat(String state, long revision, int maxSlots, CheckDirective check) {}

    public record CheckDirective(String checkId, long remainingMs) {}

    public record CheckReport(String checkId, boolean passed, String detail) {}

    public record CheckView(String checkId, String state, String runtimeVersion, String detail) {}

    public record NodeView(
            String nodeId,
            String hostGroupId,
            String name,
            String pool,
            String runtimeSpec,
            String baseUrl,
            String state,
            String health,
            int maxSlots,
            int localSlots,
            int running,
            long revision,
            String runtimeVersion,
            LocalDateTime heartbeatAt) {}

    public record AuditView(String actorId, String action, long revision, LocalDateTime at) {}

    public record AttemptView(
            long jobId,
            long leaseEpoch,
            String outcome,
            LocalDateTime startedAt,
            LocalDateTime endedAt) {}

    public record Detail(
            NodeView node,
            List<CheckView> checks,
            List<AuditView> audit,
            List<AttemptView> attempts) {}

    public List<HostView> hosts() {
        return db.query(
                "SELECT * FROM judge_host_group ORDER BY id LIMIT 256",
                (r, i) ->
                        new HostView(
                                r.getString("id"),
                                r.getString("name"),
                                r.getInt("max_slots"),
                                r.getLong("revision")));
    }

    @Transactional
    public HostView createHost(String name, int slots) {
        checkName(name);
        checkSlots(slots);
        long id =
                insert(
                        "INSERT INTO judge_host_group (name, max_slots) VALUES (?, ?)",
                        name.trim(),
                        slots);
        return new HostView(Long.toString(id), name.trim(), slots, 1);
    }

    @Transactional
    public void changeHost(long id, long revision, int slots) {
        checkSlots(slots);
        if (db.update(
                        "UPDATE judge_host_group SET max_slots=?, revision=revision+1 WHERE id=? AND revision=?",
                        slots,
                        id,
                        revision)
                != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "宿主机配置已变化");
        }
    }

    @Transactional
    public Enrollment register(String name, String pool, long host, int slots, String endpoint) {
        checkName(name);
        checkSlots(slots);
        if (pool == null || !POOLS.contains(pool)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "未知运行时");
        }
        if (slots > hostSlots(host)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "节点槽位超过宿主机上限");
        }
        String url = endpointProbe.approvedEndpoint(endpoint);
        String code = JudgeTokens.newToken();
        long id =
                insert(
                        """
                INSERT INTO judge_node
                    (host_group_id, name, pool, runtime_spec, max_slots, base_url, enrollment_hash, enrollment_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6) + INTERVAL 10 MINUTE)
                """,
                        host,
                        name.trim(),
                        pool,
                        pool + "-v1",
                        slots,
                        url,
                        JudgeTokens.digest(code));
        audit(id, "REGISTER", 1);
        return new Enrollment(Long.toString(id), code, 600);
    }

    public List<NodeView> list(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的节点分页参数");
        }
        return db
                .query(
                        NODE_SELECT + " WHERE id > ? ORDER BY id LIMIT ?",
                        (r, i) -> node(r),
                        afterId,
                        limit)
                .stream()
                .map(this::view)
                .toList();
    }

    public Detail detail(long id) {
        Node n = find(id, false);
        var checks =
                db.query(
                        "SELECT id,state,runtime_version,detail FROM judge_node_check WHERE node_id=? ORDER BY id DESC LIMIT 20",
                        (r, i) ->
                                new CheckView(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4)),
                        id);
        var audit =
                db.query(
                        "SELECT actor_id,action,revision,created_at FROM judge_node_audit WHERE node_id=? ORDER BY id DESC LIMIT 20",
                        (r, i) ->
                                new AuditView(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getLong(3),
                                        r.getObject(4, LocalDateTime.class)),
                        id);
        var attempts =
                db.query(
                        "SELECT job_id,lease_epoch,outcome,started_at,ended_at FROM judge_job_attempt WHERE node_id=? ORDER BY started_at DESC LIMIT 20",
                        (r, i) ->
                                new AttemptView(
                                        r.getLong(1),
                                        r.getLong(2),
                                        r.getString(3),
                                        r.getObject(4, LocalDateTime.class),
                                        r.getObject(5, LocalDateTime.class)),
                        id);
        return new Detail(view(n), checks, audit, attempts);
    }

    @Transactional
    public NodeView change(long id, long revision, String state, Integer slots) {
        Node n = find(id, false);
        int capacity = hostSlots(n.host());
        n = find(id, true);
        requireRevision(n, revision);
        int maxSlots = slots == null ? n.maxSlots() : slots;
        if (slots != null) {
            checkSlots(slots);
            if (slots > capacity) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "节点槽位超过宿主机上限");
            }
        }
        String target = state == null ? n.state() : state;
        if (!Set.of("ENABLED", "DRAINING", "DISABLED", "PENDING_VERIFICATION").contains(target)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的节点状态");
        }
        if ("ENABLED".equals(target)
                && !"ENABLED".equals(n.state())
                && (!n.verified() || !n.fresh() || !n.healthy() || n.hash() == null)) {
            throw new BusinessException(ResultCode.CONFLICT, "节点需要通过自检并保持在线");
        }
        if (("DISABLED".equals(target) || "PENDING_VERIFICATION".equals(target))
                && (running(n.id()) > 0 || checking(n.id()) > 0)) {
            throw new BusinessException(ResultCode.CONFLICT, "节点仍有在途任务或自检");
        }
        db.update(
                "UPDATE judge_node SET state=?, max_slots=?, revision=revision+1 WHERE id=?",
                target,
                maxSlots,
                id);
        audit(id, "UPDATE", revision + 1);
        return view(find(id, true));
    }

    @Transactional
    public Enrollment reset(long id, long revision) {
        Node n = find(id, true);
        requireRevision(n, revision);
        if (running(id) > 0 || checking(id) > 0 || "ENABLED".equals(n.state())) {
            throw new BusinessException(ResultCode.CONFLICT, "先排空节点再轮换凭据");
        }
        String code = JudgeTokens.newToken();
        db.update(
                """
                UPDATE judge_node SET enrollment_hash=?, enrollment_until=UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE,
                  credential_hash=NULL, boot_id=NULL, healthy=FALSE, verified=FALSE, heartbeat_at=NULL,
                  session_epoch=session_epoch+1, revision=revision+1, state='PENDING_VERIFICATION'
                WHERE id=?
                """,
                JudgeTokens.digest(code),
                id);
        audit(id, "ROTATE", revision + 1);
        return new Enrollment(Long.toString(id), code, 600);
    }

    @Transactional
    public void revoke(long id, long revision) {
        Node n = find(id, true);
        requireRevision(n, revision);
        db.update(
                """
                UPDATE judge_node SET credential_hash=NULL, enrollment_hash=NULL, enrollment_until=NULL,
                    state='DISABLED', healthy=FALSE, verified=FALSE, revision=revision+1, session_epoch=session_epoch+1
                WHERE id=?
                """,
                id);
        audit(id, "REVOKE", revision + 1);
    }

    @Transactional
    public CheckView requestCheck(Node inspected) {
        long id = inspected.id();
        Node n = find(id, true);
        if (n.epoch() != inspected.epoch()
                || !Objects.equals(n.runtimeVersion(), inspected.runtimeVersion())
                || !Objects.equals(n.baseUrl(), inspected.baseUrl())) {
            throw new BusinessException(ResultCode.CONFLICT, "节点会话已变化，请重新自检");
        }
        if (!n.fresh()
                || !n.healthy()
                || n.epoch() == 0
                || n.hash() == null
                || "DISABLED".equals(n.state())
                || "DRAINING".equals(n.state())
                || running(id) > 0
                || db.queryForObject(
                                """
                        SELECT COUNT(*) FROM judge_node_check WHERE node_id=? AND state IN ('QUEUED','RUNNING')
                            AND expires_at>UTC_TIMESTAMP(6)
                        """,
                                Integer.class,
                                id)
                        > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "节点需要在线、空闲且没有正在等待的自检");
        }
        long check =
                insert(
                        """
                INSERT INTO judge_node_check (node_id, session_epoch, runtime_version, expires_at, created_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6)+INTERVAL 5 MINUTE, UTC_TIMESTAMP(6))
                """,
                        id,
                        n.epoch(),
                        n.runtimeVersion());
        db.update("UPDATE judge_node SET verified=FALSE WHERE id=?", id);
        audit(id, "SELF_TEST", n.revision());
        return new CheckView(Long.toString(check), "QUEUED", n.runtimeVersion(), null);
    }

    @Transactional
    public Joined join(String code, String boot, int protocol) {
        validateBoot(boot, protocol);
        if (!JudgeTokens.valid(code)) {
            throw new JudgeProtocolException(401, "INVALID_ENROLLMENT");
        }
        Node n =
                db
                        .query(
                                NODE_SELECT
                                        + " WHERE enrollment_hash=? AND enrollment_until>UTC_TIMESTAMP(6) FOR UPDATE",
                                (r, i) -> node(r),
                                JudgeTokens.digest(code))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new JudgeProtocolException(401, "INVALID_ENROLLMENT"));
        String credential = JudgeTokens.newToken();
        db.update(
                "UPDATE judge_node SET credential_hash=?, enrollment_hash=NULL,enrollment_until=NULL WHERE id=?",
                JudgeTokens.digest(credential),
                n.id());
        Session session = open(n, boot);
        return new Joined(
                session.nodeId(), credential, session.sessionEpoch(), 1, n.pool(), n.runtime());
    }

    @Transactional
    public Session session(Access access, String boot, int protocol) {
        validateBoot(boot, protocol);
        return open(lock(access, false), boot);
    }

    private Session open(Node n, String boot) {
        if (boot.equals(n.boot())) {
            return new Session(Long.toString(n.id()), n.epoch(), 1, n.pool(), n.runtime());
        }
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM judge_node_boot WHERE node_id=? AND boot_id=?",
                        Integer.class,
                        n.id(),
                        boot)
                > 0) {
            throw new JudgeProtocolException(409, "STALE_BOOT");
        }
        long epoch = n.epoch() + 1;
        db.update(
                "INSERT INTO judge_node_boot (node_id,boot_id,session_epoch) VALUES (?,?,?)",
                n.id(),
                boot,
                epoch);
        db.update(
                """
                UPDATE judge_node SET session_epoch=?, boot_id=?, verified=FALSE, healthy=FALSE, heartbeat_at=NULL
                WHERE id=?
                """,
                epoch,
                boot,
                n.id());
        return new Session(Long.toString(n.id()), epoch, 1, n.pool(), n.runtime());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Heartbeat heartbeat(
            Access access,
            String runtime,
            String version,
            int slots,
            boolean healthy,
            CheckReport report) {
        Node hint = find(access.nodeId(), false);
        int capacity = hostSlots(hint.host());
        Node n = lock(access, true);
        if (!n.runtime().equals(runtime)
                || version == null
                || !version.matches("[A-Za-z0-9._+-]{1,100}")
                || slots < 1
                || slots > 256) {
            throw new JudgeProtocolException(422, "INVALID_RUNTIME");
        }
        boolean sameVersion = Objects.equals(version, n.runtimeVersion());
        boolean verified = n.verified() && sameVersion;
        if (report != null) {
            long check = parseId(report.checkId());
            if (!sameVersion
                    || (report.detail() != null && report.detail().length() > 500)
                    || db.update(
                                    """
                            UPDATE judge_node_check SET state=?, detail=?
                            WHERE id=? AND node_id=? AND session_epoch=? AND runtime_version=?
                              AND state='RUNNING' AND expires_at>UTC_TIMESTAMP(6)
                            """,
                                    report.passed() ? "PASSED" : "FAILED",
                                    report.detail(),
                                    check,
                                    n.id(),
                                    n.epoch(),
                                    version)
                            != 1) {
                throw new JudgeProtocolException(409, "STALE_CHECK");
            }
            verified = report.passed() && healthy;
        }
        db.update(
                """
                UPDATE judge_node SET heartbeat_at=UTC_TIMESTAMP(6), healthy=?, local_slots=?,
                    runtime_version=?, verified=? WHERE id=?
                """,
                healthy,
                slots,
                version,
                verified,
                n.id());
        CheckDirective check = null;
        if (healthy && !"DISABLED".equals(n.state()) && !"DRAINING".equals(n.state())) {
            check = directive(n, version, capacity);
        }
        return new Heartbeat(n.state(), n.revision(), Math.min(slots, n.maxSlots()), check);
    }

    private CheckDirective directive(Node n, String version, int capacity) {
        CheckDirective active =
                db
                        .query(
                                """
                SELECT id, TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),expires_at) DIV 1000 AS remaining
                FROM judge_node_check WHERE node_id=? AND session_epoch=? AND runtime_version=?
                  AND state='RUNNING' AND expires_at>UTC_TIMESTAMP(6) LIMIT 1
                """,
                                (r, i) -> new CheckDirective(r.getString(1), r.getLong(2)),
                                n.id(),
                                n.epoch(),
                                version)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (active != null
                || running(n.id()) > 0
                || checking(n.id()) > 0
                || hostUsed(n.host()) >= capacity) {
            return active;
        }
        Long next =
                db
                        .query(
                                """
                SELECT id FROM judge_node_check WHERE node_id=? AND session_epoch=? AND runtime_version=?
                   AND state='QUEUED' AND expires_at>UTC_TIMESTAMP(6) ORDER BY id LIMIT 1
                """,
                                (r, i) -> r.getLong(1),
                                n.id(),
                                n.epoch(),
                                version)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (next == null
                || db.update(
                                """
                UPDATE judge_node_check SET state='RUNNING',expires_at=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND
                WHERE id=? AND state='QUEUED' AND expires_at>UTC_TIMESTAMP(6)
                """,
                                next)
                        != 1) {
            return null;
        }
        return new CheckDirective(Long.toString(next), 60_000);
    }

    void expireChecks() {
        db.update(
                """
                UPDATE judge_node_check SET state='EXPIRED'
                WHERE state IN ('QUEUED','RUNNING') AND expires_at<=UTC_TIMESTAMP(6)
                """);
    }

    Access authenticate(String token, long epoch) {
        if (!JudgeTokens.valid(token)) {
            throw new JudgeProtocolException(401, "NODE_AUTH_REQUIRED");
        }
        String hash = JudgeTokens.digest(token);
        return db
                .query(
                        "SELECT id FROM judge_node WHERE credential_hash=?",
                        (r, i) -> new Access(r.getLong(1), hash, epoch),
                        hash)
                .stream()
                .findFirst()
                .orElseThrow(() -> new JudgeProtocolException(401, "INVALID_NODE_CREDENTIAL"));
    }

    Node lock(Access access, boolean requireEpoch) {
        Node n = find(access.nodeId(), true);
        if (!access.hash().equals(n.hash()) || "DISABLED".equals(n.state())) {
            throw new JudgeProtocolException(401, "INVALID_NODE_CREDENTIAL");
        }
        if (requireEpoch && (access.epoch() <= 0 || n.epoch() != access.epoch())) {
            throw new JudgeProtocolException(409, "STALE_SESSION");
        }
        return n;
    }

    Node find(long id, boolean lock) {
        return db
                .query(
                        NODE_SELECT + " WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        (r, i) -> node(r),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "评测节点不存在"));
    }

    int hostSlots(long id) {
        return db
                .query(
                        "SELECT max_slots FROM judge_host_group WHERE id=? FOR UPDATE",
                        (r, i) -> r.getInt(1),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "宿主机不存在"));
    }

    int hostUsed(long id) {
        return db.queryForObject(
                """
                SELECT (SELECT COUNT(*) FROM judge_job WHERE host_group_id=? AND state='RUNNING')
                    + (SELECT COUNT(*) FROM judge_node_check c JOIN judge_node n ON n.id=c.node_id
                        WHERE n.host_group_id=? AND c.state='RUNNING' AND c.expires_at>UTC_TIMESTAMP(6))
                """,
                Integer.class,
                id,
                id);
    }

    int running(long id) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM judge_job WHERE node_id=? AND state='RUNNING'",
                Integer.class,
                id);
    }

    int checking(long id) {
        return db.queryForObject(
                """
                SELECT COUNT(*) FROM judge_node_check WHERE node_id=? AND state='RUNNING' AND expires_at>UTC_TIMESTAMP(6)
                """,
                Integer.class,
                id);
    }

    private NodeView view(Node n) {
        int running = running(n.id());
        String health =
                !n.fresh()
                        ? "OFFLINE"
                        : !n.healthy() ? "UNHEALTHY" : !n.verified() ? "UNVERIFIED" : "HEALTHY";
        return new NodeView(
                Long.toString(n.id()),
                Long.toString(n.host()),
                n.name(),
                n.pool(),
                n.runtime(),
                n.baseUrl(),
                n.state(),
                health,
                n.maxSlots(),
                n.localSlots(),
                running,
                n.revision(),
                n.runtimeVersion(),
                n.heartbeatAt());
    }

    private static Node node(ResultSet r) throws SQLException {
        return new Node(
                r.getLong("id"),
                r.getLong("host_group_id"),
                r.getString("name"),
                r.getString("pool"),
                r.getString("runtime_spec"),
                r.getString("state"),
                r.getInt("max_slots"),
                r.getInt("local_slots"),
                r.getLong("revision"),
                r.getLong("session_epoch"),
                r.getString("boot_id"),
                r.getString("credential_hash"),
                r.getBoolean("healthy"),
                r.getBoolean("verified"),
                r.getBoolean("fresh"),
                r.getString("runtime_version"),
                r.getString("base_url"),
                r.getObject("heartbeat_at", LocalDateTime.class));
    }

    private void audit(long id, String action, long revision) {
        db.update(
                """
                INSERT INTO judge_node_audit (node_id,actor_id,action,revision,created_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6))
                """,
                id,
                AuthContextHolder.currentUserId(),
                action,
                revision);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        try {
            db.update(
                    connection -> {
                        var statement = connection.prepareStatement(sql, new String[] {"id"});
                        for (int i = 0; i < values.length; i++) {
                            statement.setObject(i + 1, values[i]);
                        }
                        return statement;
                    },
                    key);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "评测节点或宿主机名称已存在");
        }
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private static void requireRevision(Node n, long revision) {
        if (n.revision() != revision) {
            throw new BusinessException(ResultCode.CONFLICT, "节点配置已变化");
        }
    }

    private static void checkName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的名称");
        }
    }

    private static void checkSlots(int slots) {
        if (slots < 1 || slots > 256) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "并发槽位须在 1 到 256 之间");
        }
    }

    private static void validateBoot(String boot, int protocol) {
        if (protocol != 1 || boot == null || !boot.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new JudgeProtocolException(422, "INVALID_BOOT_OR_PROTOCOL");
        }
    }

    static long parseId(String id) {
        try {
            long value = Long.parseLong(id);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Invalid protocol identifiers never reach the database.
        }
        throw new JudgeProtocolException(422, "INVALID_IDENTIFIER");
    }
}
