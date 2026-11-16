package com.kece.user.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 用户实体，映射 {@code user} 表。 */
@Data
public class User {

    private Long id;
    private String username;
    private String passwordHash;
    private String email;
    private String avatarUrl;
    private String bio;
    private String githubUrl;

    /** 角色列表，逗号分隔（USER/TEACHER/ADMIN）。 */
    private String roles;

    /** 状态：1 启用 0 禁用。 */
    private Integer status;

    /** JWT 令牌版本号，改密/换绑邮箱/后台重置时递增。 */
    private Integer tokenVersion;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
