package com.kece.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改个人资料请求体。 */
@Data
public class UpdateProfileRequest {

    @Size(max = 32, message = "用户名最多 32 个字符")
    private String username;

    @Size(max = 200, message = "个人简介最多 200 个字符")
    private String bio;

    @Size(max = 200, message = "GitHub 链接最多 200 个字符")
    private String githubUrl;
}
