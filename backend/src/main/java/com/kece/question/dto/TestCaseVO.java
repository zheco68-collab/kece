package com.kece.question.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 测试用例视图对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseVO {

    private Long id;
    private Long questionId;
    private Integer caseNum;
    private String inputPath;
    private String outputPath;
    private Long inputSize;
    private Long outputSize;
    private Integer scoreWeight;
    private String input;
    private String expectedOutput;
    private Integer isSample;
    private Boolean isLarge;
}
