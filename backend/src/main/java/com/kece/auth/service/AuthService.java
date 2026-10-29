package com.kece.auth.service;

import com.kece.auth.dto.AuthResponse;
import com.kece.auth.dto.LoginRequest;
import com.kece.auth.dto.RegisterRequest;
import com.kece.auth.dto.UserVO;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthUser;
import com.kece.common.security.JwtUtil;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 认证业务逻辑。 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱不能为空");
        }
        String email = request.getEmail().trim().toLowerCase();
        if (userMapper.findByEmail(email) != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "邮箱已被注册");
        }

        String username;
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            username = request.getUsername().trim();
            if (userMapper.findByUsername(username) != null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "用户名已存在");
            }
        } else {
            // 从邮箱前缀自动推导用户名
            int atIndex = email.indexOf('@');
            String rawPrefix = atIndex > 0 ? email.substring(0, atIndex) : email;
            String sanitized = rawPrefix.replaceAll("[^a-zA-Z0-9_]", "");
            if (sanitized.length() < 3) {
                sanitized = "user_" + sanitized;
            }
            if (sanitized.length() > 50) {
                sanitized = sanitized.substring(0, 50);
            }

            String candidate = sanitized;
            if (userMapper.findByUsername(candidate) != null) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int attempts = 0;
                while (userMapper.findByUsername(candidate) != null && attempts < 20) {
                    int suffix = random.nextInt(1000, 10000);
                    candidate = sanitized + suffix;
                    attempts++;
                }
                if (userMapper.findByUsername(candidate) != null) {
                    candidate = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                }
            }
            username = candidate;
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEmail(email);
        user.setRoles("USER");
        user.setStatus(1);
        userMapper.insert(user);
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String account = request.getAccount();
        if (account == null || account.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "邮箱或密码错误");
        }

        User user = userMapper.findByEmail(account);
        if (user == null) {
            user = userMapper.findByEmail(account.toLowerCase());
        }
        if (user == null && !account.contains("@")) {
            user = userMapper.findByUsername(account);
        }
        if (user == null
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "邮箱或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        return buildAuthResponse(user);
    }

    public AuthResponse refresh(String refreshToken) {
        try {
            if (!jwtUtil.isRefreshToken(refreshToken)) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "无效的刷新令牌");
            }
            Claims claims = jwtUtil.parse(refreshToken);
            Long userId = Long.valueOf(claims.getSubject());
            User user = userMapper.findById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已禁用");
            }
            return buildAuthResponse(user);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }
    }

    public UserVO currentUser(AuthUser authUser) {
        User user = userMapper.findById(authUser.getId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        return toVO(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        List<String> roles = parseRoles(user.getRoles());
        String token = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());
        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .user(toVO(user))
                .build();
    }

    private UserVO toVO(User user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .roles(parseRoles(user.getRoles()))
                .build();
    }

    private List<String> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of();
        }
        return Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
