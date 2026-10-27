package com.kece.problemset.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题集完整详情视图模型（含章节树、全量条目列表与断点续刷信息）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSetDetailVO {

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

    private Boolean favorited;
    private Integer solvedCount;
    private Long lastItemId;
    private ProblemSetItemVO resumeItem;

    private List<ProblemSetSectionVO> sections;
    private List<ProblemSetItemVO> flatItems;
}
