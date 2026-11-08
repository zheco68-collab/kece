package com.kece.judging.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JudgeReconciliationService {

    private final JdbcTemplate db;

    public ReconciliationReportVO reconcile(String filterPool, Long minRevision) {
        List<ReconciliationReportVO.ViolationItemVO> violations = new ArrayList<>();

        // 1. 指针一致性检查 (ALGO)
        String algoPointerSql =
                """
                SELECT s.id AS submission_id, s.current_judge_job_id
                FROM submission s
                LEFT JOIN judge_job j ON j.id = s.current_judge_job_id
                WHERE s.current_judge_job_id IS NOT NULL
                  AND (j.id IS NULL OR j.submission_type <> 'ALGO' OR j.submission_id <> s.id)
                """;
        db.query(
                algoPointerSql,
                (rs) -> {
                    violations.add(
                            ReconciliationReportVO.ViolationItemVO.builder()
                                    .category("POINTER_MISMATCH_ALGO")
                                    .entityId("sub-" + rs.getLong("submission_id"))
                                    .detail(
                                            "current_judge_job_id="
                                                    + rs.getLong("current_judge_job_id")
                                                    + " 目标任务不存在或类型/提交不匹配")
                                    .build());
                });

        // 1. 指针一致性检查 (DATABASE)
        String sqlPointerSql =
                """
                SELECT s.id AS submission_id, s.current_judge_job_id
                FROM db_submission s
                LEFT JOIN judge_job j ON j.id = s.current_judge_job_id
                WHERE s.current_judge_job_id IS NOT NULL
                  AND (j.id IS NULL OR j.submission_type <> 'DATABASE' OR j.submission_id <> s.id)
                """;
        db.query(
                sqlPointerSql,
                (rs) -> {
                    violations.add(
                            ReconciliationReportVO.ViolationItemVO.builder()
                                    .category("POINTER_MISMATCH_DB")
                                    .entityId("dbsub-" + rs.getLong("submission_id"))
                                    .detail(
                                            "current_judge_job_id="
                                                    + rs.getLong("current_judge_job_id")
                                                    + " 目标任务不存在或类型/提交不匹配")
                                    .build());
                });

        // 2. 租约与代次排他性检查：运行中任务不可存在多个未终结的 attempt
        String attemptSql =
                """
                SELECT j.id, j.submission_type, j.submission_id, COUNT(*) AS active_attempts
                FROM judge_job j
                JOIN judge_job_attempt a ON a.job_id = j.id
                WHERE j.state = 'RUNNING' AND j.lease_until > UTC_TIMESTAMP(6) AND a.outcome IS NULL
                GROUP BY j.id, j.submission_type, j.submission_id
                HAVING COUNT(*) > 1
                """;
        db.query(
                attemptSql,
                (rs) -> {
                    violations.add(
                            ReconciliationReportVO.ViolationItemVO.builder()
                                    .category("CONCURRENT_ACTIVE_ATTEMPTS")
                                    .entityId("job-" + rs.getLong("id"))
                                    .detail("运行中任务存在 " + rs.getInt("active_attempts") + " 个并发有效租约")
                                    .build());
                });

        // 3. 终态一致性检查：COMPLETED 的 job 对应业务记录状态未收敛
        String algoVerdictSql =
                """
                SELECT j.id AS job_id, s.id AS submission_id, s.status AS sub_status
                FROM judge_job j
                JOIN submission s ON s.id = j.submission_id
                WHERE j.submission_type = 'ALGO' AND j.state = 'COMPLETED'
                  AND s.current_judge_job_id = j.id
                  AND s.status NOT IN ('ACCEPTED','WRONG_ANSWER','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','RUNTIME_ERROR','COMPILE_ERROR','SYSTEM_ERROR')
                """;
        db.query(
                algoVerdictSql,
                (rs) -> {
                    violations.add(
                            ReconciliationReportVO.ViolationItemVO.builder()
                                    .category("VERDICT_NOT_CONVERGED_ALGO")
                                    .entityId("job-" + rs.getLong("job_id"))
                                    .detail("任务已完成但业务提交仍停留在 " + rs.getString("sub_status"))
                                    .build());
                });

        String sqlVerdictSql =
                """
                SELECT j.id AS job_id, s.id AS submission_id, s.status AS sub_status
                FROM judge_job j
                JOIN db_submission s ON s.id = j.submission_id
                WHERE j.submission_type = 'DATABASE' AND j.state = 'COMPLETED'
                  AND s.current_judge_job_id = j.id
                  AND s.status NOT IN ('ACCEPTED','WRONG_ANSWER','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','RUNTIME_ERROR','COMPILE_ERROR','SYSTEM_ERROR')
                """;
        db.query(
                sqlVerdictSql,
                (rs) -> {
                    violations.add(
                            ReconciliationReportVO.ViolationItemVO.builder()
                                    .category("VERDICT_NOT_CONVERGED_DB")
                                    .entityId("job-" + rs.getLong("job_id"))
                                    .detail("SQL 任务已完成但业务提交仍停留在 " + rs.getString("sub_status"))
                                    .build());
                });

        // 4. 结算事件健康检查：PENDING 且超过 10 分钟未处理
        String eventStuckSql =
                """
                SELECT id, job_id, event_type, attempt_count, last_error
                FROM judge_settlement_event
                WHERE state = 'PENDING' AND available_at < TIMESTAMPADD(MINUTE, -10, UTC_TIMESTAMP(6))
                """;
        db.query(
                eventStuckSql,
                (rs) -> {
                    violations.add(
                            ReconciliationReportVO.ViolationItemVO.builder()
                                    .category("SETTLEMENT_EVENT_STUCK")
                                    .entityId("event-" + rs.getLong("id"))
                                    .detail(
                                            "事件停留在 PENDING 超过 10 分钟, 重试="
                                                    + rs.getInt("attempt_count")
                                                    + ", 错误="
                                                    + rs.getString("last_error"))
                                    .build());
                });

        // 5. 节点槽位超额检查
        String capacitySql =
                """
                SELECT n.id AS node_id, n.name, n.max_slots, COUNT(j.id) AS running_jobs
                FROM judge_node n
                JOIN judge_job j ON j.node_id = n.id AND j.state = 'RUNNING' AND j.lease_until > UTC_TIMESTAMP(6)
                GROUP BY n.id, n.name, n.max_slots
                HAVING COUNT(j.id) > n.max_slots
                """;
        db.query(
                capacitySql,
                (rs) -> {
                    violations.add(
                            ReconciliationReportVO.ViolationItemVO.builder()
                                    .category("NODE_CAPACITY_BREACH")
                                    .entityId("node-" + rs.getLong("node_id"))
                                    .detail(
                                            "节点 "
                                                    + rs.getString("name")
                                                    + " 运行槽位 "
                                                    + rs.getInt("running_jobs")
                                                    + " 超过上限 "
                                                    + rs.getInt("max_slots"))
                                    .build());
                });

        // 过滤 pool
        if (filterPool != null && !filterPool.isBlank()) {
            String p = filterPool.trim().toLowerCase();
            violations.removeIf(
                    v -> {
                        // 如果是针对 job 的，按 pool 筛选
                        if (v.getEntityId().startsWith("job-")) {
                            long jId = Long.parseLong(v.getEntityId().substring(4));
                            String jPool =
                                    db.queryForObject(
                                            "SELECT pool FROM judge_job WHERE id=?",
                                            String.class,
                                            jId);
                            return !p.equalsIgnoreCase(jPool);
                        }
                        return false;
                    });
        }

        // 任务积压统计
        List<ReconciliationReportVO.JobBacklogVO> jobBacklogs =
                db.query(
                        """
                SELECT pool, state, COUNT(*) AS count, MIN(created_at) AS oldest_created_at
                FROM judge_job
                GROUP BY pool, state
                ORDER BY pool, state
                """,
                        (rs, rowNum) ->
                                ReconciliationReportVO.JobBacklogVO.builder()
                                        .pool(rs.getString("pool"))
                                        .state(rs.getString("state"))
                                        .count(rs.getLong("count"))
                                        .oldestCreatedAt(rs.getString("oldest_created_at"))
                                        .build());

        // 事件积压统计
        List<ReconciliationReportVO.EventBacklogVO> eventBacklogs =
                db.query(
                        """
                SELECT event_type, state, COUNT(*) AS count
                FROM judge_settlement_event
                GROUP BY event_type, state
                ORDER BY event_type, state
                """,
                        (rs, rowNum) ->
                                ReconciliationReportVO.EventBacklogVO.builder()
                                        .eventType(rs.getString("event_type"))
                                        .state(rs.getString("state"))
                                        .count(rs.getLong("count"))
                                        .build());

        return ReconciliationReportVO.builder()
                .timestamp(Instant.now().toString())
                .healthy(violations.isEmpty())
                .totalViolations(violations.size())
                .violations(violations)
                .jobBacklog(jobBacklogs)
                .eventBacklog(eventBacklogs)
                .build();
    }

    public String toCsv(ReconciliationReportVO report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# KECE Judge Reconciliation Report\n");
        sb.append("# Generated At: ").append(report.getTimestamp()).append("\n");
        sb.append("# Status: ")
                .append(report.isHealthy() ? "HEALTHY" : "VIOLATIONS_DETECTED")
                .append("\n");
        sb.append("# Total Violations: ").append(report.getTotalViolations()).append("\n\n");

        sb.append("Section,Category,EntityId,Detail\n");
        for (var v : report.getViolations()) {
            sb.append("VIOLATION,")
                    .append(escapeCsv(v.getCategory()))
                    .append(",")
                    .append(escapeCsv(v.getEntityId()))
                    .append(",")
                    .append(escapeCsv(v.getDetail()))
                    .append("\n");
        }

        sb.append("\nJobBacklog,Pool,State,Count,OldestCreatedAt\n");
        for (var b : report.getJobBacklog()) {
            sb.append("JOB_BACKLOG,")
                    .append(escapeCsv(b.getPool()))
                    .append(",")
                    .append(escapeCsv(b.getState()))
                    .append(",")
                    .append(b.getCount())
                    .append(",")
                    .append(escapeCsv(b.getOldestCreatedAt()))
                    .append("\n");
        }

        sb.append("\nEventBacklog,EventType,State,Count\n");
        for (var e : report.getEventBacklog()) {
            sb.append("EVENT_BACKLOG,")
                    .append(escapeCsv(e.getEventType()))
                    .append(",")
                    .append(escapeCsv(e.getState()))
                    .append(",")
                    .append(e.getCount())
                    .append("\n");
        }

        return sb.toString();
    }

    private static String escapeCsv(String str) {
        if (str == null) return "";
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}
