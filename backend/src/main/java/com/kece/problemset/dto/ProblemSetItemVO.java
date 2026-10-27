package com.kece.problemset.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题集内单道题目条目视图模型（含快照容灾兜底与用户 AC 状态）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSetItemVO {

    private Long id;
    private Long sectionId;
    private String questionType; // ALGO / DB / OBJECTIVE
    private Long questionId;
    private String title;
    private String difficulty;
    private Boolean sourceAlive;
    private Integer sortOrder;
    private Boolean solved;
}
