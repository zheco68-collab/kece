package com.kece.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class AuthInterceptorUnitTest {

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    static class DummyController {
        @RequireRole({Role.TEACHER, Role.ADMIN})
        public void teacherAction() {}
    }

    @Test
    void testPreHandle_withTokenQueryParameter() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        AuthUser authUser = new AuthUser(1L, "teacher", java.util.Set.of("TEACHER"));
        when(jwtUtil.parseToAuthUser("valid-token-123")).thenReturn(authUser);

        AuthInterceptor interceptor = new AuthInterceptor(jwtUtil);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("token")).thenReturn("valid-token-123");

        DummyController controller = new DummyController();
        Method method = DummyController.class.getMethod("teacherAction");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);

        boolean passed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(passed).isTrue();
        assertThat(AuthContextHolder.get()).isNotNull();
        assertThat(AuthContextHolder.get().getUsername()).isEqualTo("teacher");
        assertThat(AuthContextHolder.get().hasRole(Role.TEACHER)).isTrue();
    }
}
