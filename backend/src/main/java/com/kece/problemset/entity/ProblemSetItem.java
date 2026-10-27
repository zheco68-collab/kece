package com.kece.problemset.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 题集题目关联快照条目实体（对应表 {@code problem_set_item}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSetItem {

    private Long id;
    private Long setId;
    private Long sectionId;
    private String questionType;
    private Long questionId;
    private String titleSnapshot;
    private String difficultySnapshot;
    private Integer sourceAlive;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
