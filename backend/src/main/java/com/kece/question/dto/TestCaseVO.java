package com.kece.question.dto;

import lombok.Builder;
import lombok.Data;

/** 测试用例视图对象。 */
@Data
@Builder
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
