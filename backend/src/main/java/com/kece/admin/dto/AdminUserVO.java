package com.kece.admin.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/** 后台用户视图对象，不含密码哈希。 */
@Data
@Builder
public class AdminUserVO {

    private Long id;
    private String username;
    private String email;
    private String avatarUrl;
    private List<String> roles;
    private Integer status;
    private LocalDateTime createdAt;
}
