package com.kece.system.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 评测机集群分池公开状态摘要。 固定提供快照时间 asOf、数据新鲜度 freshness、就绪池数及五大专池健康与排队数据。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeClusterSummaryVO {
    private long asOf;
    private String freshness;
    private int readyPools;
    private int totalPools;
    private List<JudgePoolSummaryVO> pools;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JudgePoolSummaryVO {
        private String pool;
        private String name;
        private String state;
        private int readyNodes;
        private int totalNodes;
        private int pendingJobs;
        private int activeJobs;
    }
}
