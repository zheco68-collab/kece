package com.kece.solution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员隐藏或恢复题解的请求。 */
@Data
public class UpdateSolutionStatusRequest {

    @NotBlank(message = "状态不能为空")
    private String status;

    @NotNull(message = "版本不能为空")
    private Long version;

    @Size(max = 200, message = "原因不能超过 200 个字符")
    private String reason;
}
