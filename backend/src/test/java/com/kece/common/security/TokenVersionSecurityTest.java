package com.kece.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kece.admin.dto.ResetPasswordRequest;
import com.kece.admin.dto.UpdateStatusRequest;
import com.kece.admin.service.AdminService;
import com.kece.auth.dto.AuthResponse;
import com.kece.auth.dto.LoginRequest;
import com.kece.auth.service.AuthService;
import com.kece.common.config.JwtProperties;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.exam.mapper.ExamMapper;
import com.kece.exam.mapper.ExamSubmissionMapper;
import com.kece.exam.service.ExamRankService;
import com.kece.user.dto.ChangeEmailRequest;
import com.kece.user.dto.ChangePasswordRequest;
import com.kece.user.entity.User;
import com.kece.user.mapper.UserMapper;
import com.kece.user.service.ProfileService;
import com.kece.user.service.UserDailyAcService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.method.HandlerMethod;

class TokenVersionSecurityTest {

    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private UserAuthStateService authStateService;
    private AuthInterceptor authInterceptor;
    private AuthService authService;
    private ProfileService profileService;
    private AdminService adminService;
    private AtomicInteger dbTokenVersion;
    private AtomicInteger dbStatus;
    private User user;

    static class ProtectedController {
        @RequireRole
        public void selfEndpoint() {}
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        passwordEncoder = new BCryptPasswordEncoder();
        JwtProperties props = new JwtProperties();
        props.setSecret("kece-test-jwt-secret-key-at-least-32-characters");
        props.setExpiration(3_600_000L);
        props.setRefreshExpiration(86_400_000L);
        jwtUtil = new JwtUtil(props);

        dbTokenVersion = new AtomicInteger(0);
        dbStatus = new AtomicInteger(1);

        user = new User();
        user.setId(27L);
        user.setUsername("zheco68");
        user.setEmail("zheco68@kece.edu");
        user.setPasswordHash(passwordEncoder.encode("oldpass123"));
        user.setRoles("USER");
        user.setStatus(1);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));

        when(userMapper.findById(27L)).thenAnswer(inv -> snapshot());
        when(userMapper.findByEmail("zheco68@kece.edu")).thenAnswer(inv -> snapshot());
        when(userMapper.findAuthStateById(27L)).thenAnswer(inv -> snapshot());
        when(userMapper.incrementTokenVersion(27L))
                .thenAnswer(
                        inv -> {
                            dbTokenVersion.incrementAndGet();
                            return 1;
                        });
        when(userMapper.updateStatus(27L, 0))
                .thenAnswer(
                        inv -> {
                            dbStatus.set(0);
                            return 1;
                        });

        authStateService = new UserAuthStateService(userMapper, redis);
        authInterceptor = new AuthInterceptor(jwtUtil, authStateService);
        authService = new AuthService(userMapper, passwordEncoder, jwtUtil, authStateService);
        profileService =
                new ProfileService(
                        userMapper,
                        mock(ExamMapper.class),
                        mock(ExamSubmissionMapper.class),
                        mock(ExamRankService.class),
                        passwordEncoder,
                        mock(UserDailyAcService.class),
                        authStateService);
        adminService = new AdminService(userMapper, passwordEncoder, authStateService);
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    private User snapshot() {
        User copy = new User();
        copy.setId(user.getId());
        copy.setUsername(user.getUsername());
        copy.setEmail(user.getEmail());
        copy.setPasswordHash(user.getPasswordHash());
        copy.setRoles(user.getRoles());
        copy.setStatus(dbStatus.get());
        copy.setTokenVersion(dbTokenVersion.get());
        copy.setCreatedAt(user.getCreatedAt());
        return copy;
    }

    @Test
    void changePassword_incrementsTokenVersionAndRejectsOldAccessAndRefreshTokens()
            throws Exception {
        LoginRequest login = new LoginRequest();
        login.setEmail("zheco68@kece.edu");
        login.setPassword("oldpass123");
        AuthResponse initialAuth = authService.login(login);

        // Old token passes initially
        assertThat(invokeProtected(initialAuth.getToken())).isTrue();

        // User changes password
        AuthContextHolder.set(new AuthUser(27L, "zheco68", java.util.Set.of("USER"), 0));
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("oldpass123");
        req.setNewPassword("newpass456");
        profileService.changePassword(req);

        verify(userMapper).incrementTokenVersion(27L);
        verify(redis).delete("kece:user:auth_state:27");
        user.setPasswordHash(passwordEncoder.encode("newpass456"));

        // Old access token and old refresh token are now rejected
        assertThatThrownBy(() -> invokeProtected(initialAuth.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        assertThatThrownBy(() -> authService.refresh(initialAuth.getRefreshToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);

        // Re-login with new password produces tokenVersion=1 token that passes
        LoginRequest relogin = new LoginRequest();
        relogin.setEmail("zheco68@kece.edu");
        relogin.setPassword("newpass456");
        AuthResponse newAuth = authService.login(relogin);
        assertThat(invokeProtected(newAuth.getToken())).isTrue();
    }

    @Test
    void adminResetPasswordAndDisable_invalidatesExistingTokensImmediately() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setEmail("zheco68@kece.edu");
        login.setPassword("oldpass123");
        AuthResponse initialAuth = authService.login(login);

        AuthContextHolder.set(new AuthUser(1L, "admin", java.util.Set.of("ADMIN"), 0));
        ResetPasswordRequest resetReq = new ResetPasswordRequest();
        resetReq.setNewPassword("adminreset99");
        adminService.resetPassword(27L, resetReq);

        assertThatThrownBy(() -> invokeProtected(initialAuth.getToken()))
                .isInstanceOf(BusinessException.class);

        // New login after reset works, then disabling user blocks it
        user.setPasswordHash(passwordEncoder.encode("adminreset99"));
        LoginRequest relogin = new LoginRequest();
        relogin.setEmail("zheco68@kece.edu");
        relogin.setPassword("adminreset99");
        AuthResponse secondAuth = authService.login(relogin);
        assertThat(invokeProtected(secondAuth.getToken())).isTrue();

        AuthContextHolder.set(new AuthUser(1L, "admin", java.util.Set.of("ADMIN"), 0));
        UpdateStatusRequest disableReq = new UpdateStatusRequest();
        disableReq.setStatus(0);
        adminService.updateStatus(27L, disableReq);

        assertThatThrownBy(() -> invokeProtected(secondAuth.getToken()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void changeEmail_incrementsTokenVersionAndRejectsOldTokens() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setEmail("zheco68@kece.edu");
        login.setPassword("oldpass123");
        AuthResponse initialAuth = authService.login(login);

        assertThat(invokeProtected(initialAuth.getToken())).isTrue();

        AuthContextHolder.set(new AuthUser(27L, "zheco68", java.util.Set.of("USER"), 0));
        ChangeEmailRequest req = new ChangeEmailRequest();
        req.setCurrentPassword("oldpass123");
        req.setNewEmail("updated@kece.edu");
        profileService.changeEmail(req);

        verify(userMapper).updateEmail(27L, "updated@kece.edu");
        verify(userMapper).incrementTokenVersion(27L);
        assertThatThrownBy(() -> invokeProtected(initialAuth.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
    }

    private boolean invokeProtected(String token) throws Exception {
        AuthContextHolder.clear();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        ProtectedController controller = new ProtectedController();
        Method method = ProtectedController.class.getMethod("selfEndpoint");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);
        return authInterceptor.preHandle(request, response, handlerMethod);
    }
}
