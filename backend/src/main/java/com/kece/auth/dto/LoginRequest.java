package com.kece.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求。 */
@Data
public class LoginRequest {

    @NotBlank(message = "邮箱不能为空")
    private String email;

    /** 保留用于向后兼容。 */
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    public void setUsername(String username) {
        this.username = username;
        if ((this.email == null || this.email.isBlank()) && username != null && !username.isBlank()) {
            this.email = username;
        }
    }

    public String getAccount() {
        return email != null && !email.isBlank() ? email.trim() : (username != null ? username.trim() : "");
    }
}
