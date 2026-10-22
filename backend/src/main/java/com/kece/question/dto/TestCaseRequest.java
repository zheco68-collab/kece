package com.kece.question.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 测试用例创建/更新请求。 */
@Data
public class TestCaseRequest {

    private String input;

    @NotBlank(message = "期望输出不能为空")
    private String expectedOutput;

    private Integer caseNum;

    private Integer scoreWeight = 10;

    /** 是否为样例用例：1 是 0 否。 */
    @Min(value = 0, message = "isSample 取值只能为 0 或 1")
    @Max(value = 1, message = "isSample 取值只能为 0 或 1")
    private Integer isSample = 0;
}
