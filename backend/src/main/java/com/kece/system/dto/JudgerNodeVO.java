package com.kece.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 评测机节点遥测数据对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgerNodeVO {
    private String id;
    private String name;
    private String type; // ALGO, DATABASE, FRONTEND_AI
    private String typeLabel;
    private String status; // IDLE (空闲), JUDGING (测评中), OFFLINE (掉线)
    private String statusLabel;
    private int currentTasks;
    private int maxConcurrency;
    private Long lastHeartbeatTime;
    private String details;
}
