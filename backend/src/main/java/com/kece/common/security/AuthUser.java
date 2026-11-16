package com.kece.common.security;

import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 当前请求的认证用户信息（由 JWT 解析而来）。 */
@Getter
@RequiredArgsConstructor
public class AuthUser {

    private final Long id;
    private final String username;
    private final Set<String> roles;
    private final int tokenVersion;

    public AuthUser(Long id, String username, Set<String> roles) {
        this(id, username, roles, 0);
    }

    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role.name());
    }
}
