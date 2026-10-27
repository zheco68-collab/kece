package com.kece.problemset.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题集主表实体（对应表 {@code problem_set}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSet {

    private Long id;
    private String title;
    private String subtitle;
    private String description;
    private String category;
    private String difficulty;
    private String tags;
    private Integer itemCount;
    private Integer algoCount;
    private Integer dbCount;
    private Integer objectiveCount;
    private Integer participantCount;
    private Integer isOfficial;
    private Integer status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
