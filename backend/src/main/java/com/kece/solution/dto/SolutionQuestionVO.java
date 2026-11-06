package com.kece.solution.dto;

import lombok.Data;

/** 题解关联题目的最小元数据。 */
@Data
public class SolutionQuestionVO {

    private Long id;
    private String title;
    private Integer status;
}
