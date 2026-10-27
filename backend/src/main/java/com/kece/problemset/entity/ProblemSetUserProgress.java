package com.kece.problemset.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户题集进度与收藏预聚合实体（对应表 {@code problem_set_user_progress}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSetUserProgress {

    private Long id;
    private Long setId;
    private Long userId;
    private Integer isFavorited;
    private Integer solvedCount;
    private String solvedItemIds;
    private Long lastItemId;
    private LocalDateTime lastActiveAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
