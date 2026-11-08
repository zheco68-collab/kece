package com.kece.judging.control;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationReportVO {
    private String timestamp;
    private boolean healthy;
    private int totalViolations;
    private List<ViolationItemVO> violations;
    private List<JobBacklogVO> jobBacklog;
    private List<EventBacklogVO> eventBacklog;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViolationItemVO {
        private String category;
        private String entityId;
        private String detail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JobBacklogVO {
        private String pool;
        private String state;
        private long count;
        private String oldestCreatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventBacklogVO {
        private String eventType;
        private String state;
        private long count;
    }
}
