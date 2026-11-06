package com.kece.solution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 直接发布题解的请求。 */
@Data
public class CreateSolutionRequest {

    @NotBlank(message = "题型不能为空")
    @Size(max = 8, message = "题型不合法")
    private String questionType;

    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 80, message = "标题不能超过 80 个字符")
    private String title;

    @NotBlank(message = "正文不能为空")
    private String contentMd;

    @NotBlank(message = "请求标识不能为空")
    @Size(max = 64, message = "请求标识不合法")
    private String clientRequestId;
}
