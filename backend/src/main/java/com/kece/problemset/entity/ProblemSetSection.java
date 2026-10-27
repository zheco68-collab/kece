package com.kece.problemset.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题集章节分组实体（对应表 {@code problem_set_section}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSetSection {

    private Long id;
    private Long setId;
    private String title;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
