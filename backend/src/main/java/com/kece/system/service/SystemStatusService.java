package com.kece.system.service;

import com.kece.system.dto.ClusterStatusVO;
import com.kece.system.dto.JudgerNodeVO;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 评测集群与评测机节点遥测服务。 高可用设计：从 Redis 动态嗅探心跳 (judge:heartbeat:*)、工作机注册集合 (judge:workers) 与执行租约
 * (judge:lease:*)， 在 Redis 异常或冷启动无节点时平滑降级为 OFFLINE，保障接口零崩溃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStatusService {

    private static final String WORKERS_SET_KEY = "judge:workers";
    private static final String HEARTBEAT_PREFIX = "judge:heartbeat:";
    private static final String PROCESSING_PREFIX = "judge:processing:";
    private static final String SQL_PROCESSING_PREFIX = "judge:sql:processing:";
    private static final long CACHE_TTL_MS = 2000L;

    private final StringRedisTemplate redis;

    private volatile ClusterStatusVO cachedStatus;
    private volatile long cachedAt;

    /** 获取全集群评测机实时遥测状态（带 2s 内存缓存与基于 judge:workers 集合的 O(1) 探针，彻底规避 KEYS * 阻塞）。 */
    public ClusterStatusVO getClusterStatus() {
        long now = System.currentTimeMillis();
        ClusterStatusVO localCache = this.cachedStatus;
        if (localCache != null && (now - this.cachedAt) < CACHE_TTL_MS) {
            return localCache;
        }

        List<JudgerNodeVO> nodes = new ArrayList<>();

        boolean anyAliveWorker = false;
        Long latestWorkerHeartbeat = null;
        int activeAlgoTasks = 0;
        int activeSqlTasks = 0;

        int pendingTasks = 0;

        try {
            // 1. 从注册集合 judge:workers 读取工作机列表，按需校验心跳并惰性清理过期节点（不使用 KEYS *）
            Set<String> workerIds = redis.opsForSet().members(WORKERS_SET_KEY);
            if (workerIds != null && !workerIds.isEmpty()) {
                for (String workerId : workerIds) {
                    String timeStr = redis.opsForValue().get(HEARTBEAT_PREFIX + workerId);
                    if (timeStr == null || timeStr.isBlank()) {
                        // 心跳已过期，惰性从注册集合移除残留 workerId
                        redis.opsForSet().remove(WORKERS_SET_KEY, workerId);
                        continue;
                    }
                    anyAliveWorker = true;
                    try {
                        long epoch =
                                Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(timeStr))
                                        .toEpochMilli();
                        if (latestWorkerHeartbeat == null || epoch > latestWorkerHeartbeat) {
                            latestWorkerHeartbeat = epoch;
                        }
                    } catch (Exception ignored) {
                        latestWorkerHeartbeat = now;
                    }

                    // 2. 通过该工作机的 processing 列表长度 O(1) 统计正在执行的任务数
                    Long algoLen = redis.opsForList().size(PROCESSING_PREFIX + workerId);
                    if (algoLen != null && algoLen > 0) {
                        activeAlgoTasks += algoLen.intValue();
                    }
                    Long sqlLen = redis.opsForList().size(SQL_PROCESSING_PREFIX + workerId);
                    if (sqlLen != null && sqlLen > 0) {
                        activeSqlTasks += sqlLen.intValue();
                    }
                }
            }

            Long globalAlgoProc = redis.opsForList().size("judge:processing");
            if (globalAlgoProc != null && globalAlgoProc > 0) {
                activeAlgoTasks = Math.max(activeAlgoTasks, globalAlgoProc.intValue());
            }
            Long globalSqlProc = redis.opsForList().size("judge:sql:processing");
            if (globalSqlProc != null && globalSqlProc > 0) {
                activeSqlTasks = Math.max(activeSqlTasks, globalSqlProc.intValue());
            }

            Long q1 = redis.opsForList().size("judge:queue");
            if (q1 != null) pendingTasks += q1.intValue();
            Long q2 = redis.opsForList().size("judge:sql:queue");
            if (q2 != null) pendingTasks += q2.intValue();
        } catch (Exception e) {
            log.warn(
                    "Failed to query Redis for judger status, falling back to OFFLINE: {}",
                    e.getMessage());
            anyAliveWorker = false;
        }

        // 3. 构建节点 1：算法评测机 (Cgroups v2 强隔离沙箱)
        String algoStatus = "OFFLINE";
        String algoStatusLabel = "掉线";
        if (anyAliveWorker) {
            if (activeAlgoTasks > 0) {
                algoStatus = "JUDGING";
                algoStatusLabel = "测评中";
            } else {
                algoStatus = "IDLE";
                algoStatusLabel = "空闲";
            }
        }
        nodes.add(
                JudgerNodeVO.builder()
                        .id("node-algo-01")
                        .name("算法评测机 #1")
                        .type("ALGO")
                        .typeLabel("Cgroups 沙箱 (C++/Java/Python/ArkTS)")
                        .status(algoStatus)
                        .statusLabel(algoStatusLabel)
                        .currentTasks(activeAlgoTasks)
                        .maxConcurrency(8)
                        .lastHeartbeatTime(anyAliveWorker ? latestWorkerHeartbeat : null)
                        .details("Docker Cgroups v2 强隔离 · 毫秒级反馈")
                        .build());

        // 4. 构建节点 2：数据库评测机 (MySQL 8.0 临时库对拍沙箱)
        String sqlStatus = "OFFLINE";
        String sqlStatusLabel = "掉线";
        if (anyAliveWorker) {
            if (activeSqlTasks > 0) {
                sqlStatus = "JUDGING";
                sqlStatusLabel = "测评中";
            } else {
                sqlStatus = "IDLE";
                sqlStatusLabel = "空闲";
            }
        }
        nodes.add(
                JudgerNodeVO.builder()
                        .id("node-sql-01")
                        .name("数据库评测机 #1")
                        .type("DATABASE")
                        .typeLabel("MySQL 8.0 临时库对拍")
                        .status(sqlStatus)
                        .statusLabel(sqlStatusLabel)
                        .currentTasks(activeSqlTasks)
                        .maxConcurrency(8)
                        .lastHeartbeatTime(anyAliveWorker ? latestWorkerHeartbeat : null)
                        .details("独立端口 3307 临时库 · 物理强隔离")
                        .build());

        // 5. 构建节点 3：前端 AI 评测通道 (Playwright + LLM 结构化审计)
        boolean aiAlive = anyAliveWorker;
        Long aiHeartbeat = anyAliveWorker ? latestWorkerHeartbeat : null;

        String aiStatus = aiAlive ? "IDLE" : "OFFLINE";
        String aiStatusLabel = aiAlive ? "空闲" : "掉线";
        nodes.add(
                JudgerNodeVO.builder()
                        .id("node-ai-01")
                        .name("前端 AI 评测机")
                        .type("FRONTEND_AI")
                        .typeLabel("Playwright + 多模态 LLM")
                        .status(aiStatus)
                        .statusLabel(aiStatusLabel)
                        .currentTasks(0)
                        .maxConcurrency(4)
                        .lastHeartbeatTime(aiHeartbeat)
                        .details("DOM 确定性断言与多模态代码/UI审计")
                        .build());

        int onlineCount = 0;
        int busyCount = 0;
        int idleCount = 0;
        int offlineCount = 0;

        for (JudgerNodeVO n : nodes) {
            switch (n.getStatus()) {
                case "JUDGING" -> {
                    onlineCount++;
                    busyCount++;
                }
                case "IDLE" -> {
                    onlineCount++;
                    idleCount++;
                }
                default -> offlineCount++;
            }
        }

        ClusterStatusVO result =
                ClusterStatusVO.builder()
                        .totalNodes(nodes.size())
                        .onlineNodes(onlineCount)
                        .busyNodes(busyCount)
                        .idleNodes(idleCount)
                        .offlineNodes(offlineCount)

                        .pendingTasks(pendingTasks)
                        .nodes(nodes)
                        .timestamp(now)
                        .build();
        this.cachedStatus = result;
        this.cachedAt = now;
        return result;
    }
}
