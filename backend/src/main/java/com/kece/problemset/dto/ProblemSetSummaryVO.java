package com.kece.problemset.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题集列表卡片概要视图模型。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSetSummaryVO {

    private Long id;
    private String title;
    private String subtitle;
    private String description;
    private String category;
    private String difficulty;
    private List<String> tags;
    private Integer itemCount;
    private Integer algoCount;
    private Integer dbCount;
    private Integer objectiveCount;
    private Integer participantCount;
    private Integer isOfficial;
    private Integer status;
    private Integer version;
    private Long createdBy;
    private String authorName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 当前登录用户的进度与收藏状态（未登录时为默认 0 / false）
    private Boolean favorited;
    private Integer solvedCount;
    private Long lastItemId;
}
