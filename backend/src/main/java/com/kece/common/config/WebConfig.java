package com.kece.common.config;

import com.kece.common.security.AuthInterceptor;
import com.kece.common.security.JwtUtil;
import com.kece.judging.control.JudgeClaimReturnValueHandler;
import com.kece.judging.control.JudgeMachineInterceptor;
import java.nio.file.Paths;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web MVC 配置：注册认证拦截器、静态资源映射、提供 BCrypt 密码编码器。 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final JudgeMachineInterceptor machineInterceptor;
    private final List<HttpMessageConverter<?>> messageConverters;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(jwtUtil))
                .addPathPatterns("/**")
                .excludePathPatterns("/internal/judgers/**");
        registry.addInterceptor(machineInterceptor).addPathPatterns("/internal/judgers/**");
    }

    @Override
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        handlers.add(new JudgeClaimReturnValueHandler(messageConverters));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadDir = Paths.get("uploads").toAbsolutePath().toUri().toString();
        if (!uploadDir.endsWith("/")) {
            uploadDir += "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploadDir);
        registry.addResourceHandler("/avatars/**").addResourceLocations(uploadDir + "avatars/");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
