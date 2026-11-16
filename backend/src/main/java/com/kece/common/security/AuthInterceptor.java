package com.kece.common.security;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证与授权拦截器。
 *
 * <p>1) 尝试解析请求头 {@code Authorization: Bearer <token>} 到 {@link AuthContextHolder}； 2) 校验 {@link
 * RequireRole} 注解，未登录返回 4002，角色不足返回 4003。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserAuthStateService authStateService;

    public AuthInterceptor(JwtUtil jwtUtil) {
        this(jwtUtil, null);
    }

    public AuthInterceptor(JwtUtil jwtUtil, UserAuthStateService authStateService) {
        this.jwtUtil = jwtUtil;
        this.authStateService = authStateService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthContextHolder.clear();
        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            token = header.substring(BEARER_PREFIX.length());
        } else {
            // 支持在导出下载、新窗口打印等无自定义请求头的场景下通过 URL ?token= 携带令牌
            token = request.getParameter("token");
        }

        if (token != null && !token.isBlank()) {
            try {
                AuthUser parsed = jwtUtil.parseToAuthUser(token.trim());
                if (authStateService == null
                        || authStateService.isTokenValid(
                                parsed.getId(), parsed.getTokenVersion())) {
                    AuthContextHolder.set(parsed);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // 令牌无效或过期：按未认证处理，由下方权限校验决定是否放行
            }
        }

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }

        AuthUser user = AuthContextHolder.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Role[] allowed = requireRole.value();
        if (allowed.length == 0) {
            return true;
        }
        for (Role role : allowed) {
            if (user.hasRole(role)) {
                return true;
            }
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        AuthContextHolder.clear();
    }
}
