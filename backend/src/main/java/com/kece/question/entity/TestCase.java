package com.kece.question.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 测试用例实体，映射 {@code test_case} 表。 */
@Data
public class TestCase {

    private Long id;
    private Long questionId;
    private String inputPath;
    private String outputPath;
    private Long inputSize;
    private Long outputSize;
    private Integer caseNum;
    private Integer scoreWeight;
    private String input;
    private String expectedOutput;

    /** 是否为样例用例（对用户可见）：1 是 0 否。 */
    private Integer isSample;

    private LocalDateTime createdAt;
}
