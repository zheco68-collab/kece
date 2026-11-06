package com.kece.solution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 编辑本人题解的请求。 */
@Data
public class UpdateSolutionRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 80, message = "标题不能超过 80 个字符")
    private String title;

    @NotBlank(message = "正文不能为空")
    private String contentMd;

    @NotNull(message = "版本不能为空")
    private Long version;
}
