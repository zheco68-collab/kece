package com.kece.system.service;

import com.kece.system.dto.JudgeClusterSummaryVO;
import com.kece.system.dto.JudgeClusterSummaryVO.JudgePoolSummaryVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 五大专池评测集群公开状态摘要服务。基于控制面持久状态（带 2s 内存缓存）聚合在线、排空与排队指标，数据库瞬时异常时平滑降级为 STALE/DEGRADED。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStatusService {

    private static final long CACHE_TTL_MS = 2000L;

    private static final List<String> POOL_ORDER =
            List.of("cpp", "java", "python", "mysql", "arkts");
    private static final Map<String, String> POOL_NAMES =
            Map.of(
                    "cpp", "C++",
                    "java", "Java",
                    "python", "Python",
                    "mysql", "MySQL",
                    "arkts", "ArkTS");

    private final JdbcTemplate db;

    private volatile JudgeClusterSummaryVO cachedSummary;
    private volatile long cachedSummaryAt;

    /** 获取五大专池（C++、Java、Python、MySQL、ArkTS）真实集群摘要。 */
    public JudgeClusterSummaryVO getJudgeClusterSummary() {
        long now = System.currentTimeMillis();
        JudgeClusterSummaryVO localCache = this.cachedSummary;
        if (localCache != null && (now - this.cachedSummaryAt) < CACHE_TTL_MS) {
            return localCache;
        }

        try {
            record NodeAgg(int total, int ready, int draining) {}
            Map<String, NodeAgg> nodeStats = new HashMap<>();
            db.query(
                    """
                    SELECT pool,
                           COUNT(*) AS total_nodes,
                           SUM(CASE WHEN state = 'ENABLED' AND healthy = TRUE AND verified = TRUE AND heartbeat_at >= UTC_TIMESTAMP(6) - INTERVAL 15 SECOND THEN 1 ELSE 0 END) AS ready_nodes,
                           SUM(CASE WHEN state = 'DRAINING' THEN 1 ELSE 0 END) AS draining_nodes
                    FROM judge_node
                    WHERE state != 'DISABLED'
                    GROUP BY pool
                    """,
                    rs -> {
                        String pool = rs.getString("pool");
                        if (pool != null) {
                            nodeStats.put(
                                    pool.toLowerCase(),
                                    new NodeAgg(
                                            rs.getInt("total_nodes"),
                                            rs.getInt("ready_nodes"),
                                            rs.getInt("draining_nodes")));
                        }
                    });

            record JobAgg(int pending, int active) {}
            Map<String, JobAgg> jobStats = new HashMap<>();
            db.query(
                    """
                    SELECT pool,
                           SUM(CASE WHEN state = 'QUEUED' AND available_at <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END) AS pending_jobs,
                           SUM(CASE WHEN state = 'RUNNING' AND lease_until > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END) AS active_jobs
                    FROM judge_job
                    WHERE state IN ('QUEUED', 'RUNNING')
                    GROUP BY pool
                    """,
                    rs -> {
                        String pool = rs.getString("pool");
                        if (pool != null) {
                            jobStats.put(
                                    pool.toLowerCase(),
                                    new JobAgg(
                                            rs.getInt("pending_jobs"), rs.getInt("active_jobs")));
                        }
                    });

            List<JudgePoolSummaryVO> poolSummaries = new ArrayList<>(POOL_ORDER.size());
            int readyPoolsCount = 0;

            for (String poolKey : POOL_ORDER) {
                NodeAgg nAgg = nodeStats.getOrDefault(poolKey, new NodeAgg(0, 0, 0));
                JobAgg jAgg = jobStats.getOrDefault(poolKey, new JobAgg(0, 0));

                String state;
                if (nAgg.ready() > 0) {
                    state = jAgg.active() > 0 ? "BUSY" : "READY";
                } else if (nAgg.draining() > 0) {
                    state = "DRAINING";
                } else {
                    state = "OFFLINE";
                }

                if ("READY".equals(state) || "BUSY".equals(state)) {
                    readyPoolsCount++;
                }

                poolSummaries.add(
                        JudgePoolSummaryVO.builder()
                                .pool(poolKey)
                                .name(POOL_NAMES.getOrDefault(poolKey, poolKey))
                                .state(state)
                                .readyNodes(nAgg.ready())
                                .totalNodes(nAgg.total())
                                .pendingJobs(jAgg.pending())
                                .activeJobs(jAgg.active())
                                .build());
            }

            JudgeClusterSummaryVO summary =
                    JudgeClusterSummaryVO.builder()
                            .asOf(now)
                            .freshness("FRESH")
                            .readyPools(readyPoolsCount)
                            .totalPools(POOL_ORDER.size())
                            .pools(poolSummaries)
                            .build();

            this.cachedSummary = summary;
            this.cachedSummaryAt = now;
            return summary;
        } catch (Exception e) {
            log.warn("Failed to query database for judge cluster summary: {}", e.getMessage());
            if (this.cachedSummary != null) {
                return JudgeClusterSummaryVO.builder()
                        .asOf(this.cachedSummary.getAsOf())
                        .freshness("STALE")
                        .readyPools(this.cachedSummary.getReadyPools())
                        .totalPools(this.cachedSummary.getTotalPools())
                        .pools(this.cachedSummary.getPools())
                        .build();
            }

            List<JudgePoolSummaryVO> degraded =
                    POOL_ORDER.stream()
                            .map(
                                    p ->
                                            JudgePoolSummaryVO.builder()
                                                    .pool(p)
                                                    .name(POOL_NAMES.getOrDefault(p, p))
                                                    .state("OFFLINE")
                                                    .readyNodes(0)
                                                    .totalNodes(0)
                                                    .pendingJobs(0)
                                                    .activeJobs(0)
                                                    .build())
                            .toList();

            return JudgeClusterSummaryVO.builder()
                    .asOf(now)
                    .freshness("DEGRADED")
                    .readyPools(0)
                    .totalPools(POOL_ORDER.size())
                    .pools(degraded)
                    .build();
        }
    }
}
