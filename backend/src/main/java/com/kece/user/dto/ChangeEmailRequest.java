package com.kece.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 换绑登录邮箱请求体。 */
@Data
public class ChangeEmailRequest {

    @NotBlank(message = "请输入当前登录密码")
    private String currentPassword;

    @NotBlank(message = "请输入新邮箱地址")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 个字符")
    private String newEmail;
}
