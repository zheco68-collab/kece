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
 * 评测集群与评测机节点遥测服务。
 * 高可用设计：从 Redis 动态嗅探心跳 (judge:heartbeat:*)、工作机注册集合 (judge:workers) 与执行租约 (judge:lease:*)，
 * 在 Redis 异常或冷启动无节点时平滑降级为 OFFLINE，保障接口零崩溃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStatusService {

    private static final String HEARTBEAT_PREFIX = "judge:heartbeat:";
    private static final String LEASE_PREFIX = "judge:lease:";
    private static final String SQL_LEASE_PREFIX = "judge:sql:lease:";
    private static final String AI_HEARTBEAT_PREFIX = "judge:ai:heartbeat:";

    private final StringRedisTemplate redis;

    /**
     * 获取全集群评测机实时遥测状态。
     */
    public ClusterStatusVO getClusterStatus() {
        List<JudgerNodeVO> nodes = new ArrayList<>();
        long now = System.currentTimeMillis();

        boolean anyAliveWorker = false;
        Long latestWorkerHeartbeat = null;
        int activeAlgoTasks = 0;
        int activeSqlTasks = 0;

        try {
            // 1. 扫描当前活跃的心跳键
            Set<String> heartbeatKeys = redis.keys(HEARTBEAT_PREFIX + "*");
            if (heartbeatKeys != null && !heartbeatKeys.isEmpty()) {
                anyAliveWorker = true;
                // 读取任意有效心跳的更新时间戳
                for (String key : heartbeatKeys) {
                    String timeStr = redis.opsForValue().get(key);
                    if (timeStr != null && !timeStr.isBlank()) {
                        try {
                            long epoch = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(timeStr)).toEpochMilli();
                            if (latestWorkerHeartbeat == null || epoch > latestWorkerHeartbeat) {
                                latestWorkerHeartbeat = epoch;
                            }
                        } catch (Exception ignored) {
                            latestWorkerHeartbeat = now;
                        }
                    }
                }
            }

            // 2. 统计当前正在执行的租约任务数
            Set<String> algoLeases = redis.keys(LEASE_PREFIX + "*");
            if (algoLeases != null) {
                activeAlgoTasks = algoLeases.size();
            }

            Set<String> sqlLeases = redis.keys(SQL_LEASE_PREFIX + "*");
            if (sqlLeases != null) {
                activeSqlTasks = sqlLeases.size();
            }
        } catch (Exception e) {
            log.warn("Failed to query Redis for judger status, falling back to OFFLINE: {}", e.getMessage());
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
                        .maxConcurrency(4)
                        .lastHeartbeatTime(anyAliveWorker ? latestWorkerHeartbeat : null)
                        .details("Docker Cgroups v2 强隔离 · 毫秒级反馈")
                        .build()
        );

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
                        .maxConcurrency(4)
                        .lastHeartbeatTime(anyAliveWorker ? latestWorkerHeartbeat : null)
                        .details("独立端口 3307 临时库 · 物理强隔离")
                        .build()
        );

        // 5. 构建节点 3：前端 AI 评测通道 (Playwright + LLM 结构化审计)
        boolean aiAlive = false;
        Long aiHeartbeat = null;
        try {
            Set<String> aiKeys = redis.keys(AI_HEARTBEAT_PREFIX + "*");
            if (aiKeys != null && !aiKeys.isEmpty()) {
                aiAlive = true;
                aiHeartbeat = now;
            }
        } catch (Exception ignored) {
        }

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
                        .maxConcurrency(2)
                        .lastHeartbeatTime(aiHeartbeat)
                        .details("DOM 确定性断言与多模态代码/UI审计")
                        .build()
        );

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

        return ClusterStatusVO.builder()
                .totalNodes(nodes.size())
                .onlineNodes(onlineCount)
                .busyNodes(busyCount)
                .idleNodes(idleCount)
                .offlineNodes(offlineCount)
                .nodes(nodes)
                .timestamp(now)
                .build();
    }
}
