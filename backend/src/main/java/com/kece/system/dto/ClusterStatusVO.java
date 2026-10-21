package com.kece.system.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 评测集群整体遥测状态。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterStatusVO {
    private int totalNodes;
    private int onlineNodes;
    private int busyNodes;
    private int idleNodes;
    private int offlineNodes;
    private List<JudgerNodeVO> nodes;
    private long timestamp;
}
