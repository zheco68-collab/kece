package com.kece.judging.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.judging.artifact.JudgeArtifactController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class JudgeMachineInterceptor implements HandlerInterceptor {
    static final String ACCESS = JudgeMachineInterceptor.class.getName() + ".access";
    private final JudgeNodes nodes;
    private final ObjectMapper json;

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // These exact handlers authenticate their own single-purpose credentials.
        if (handler instanceof HandlerMethod method) {
            if (method.getBeanType() == JudgeArtifactController.class
                    && "download".equals(method.getMethod().getName())) {
                return true;
            }
            if (method.getBeanType() == JudgeMachineController.class
                    && "join".equals(method.getMethod().getName())) {
                return true;
            }
        }
        // An async dispatch keeps the already authenticated request attributes.
        if (request.getAttribute(ACCESS) instanceof JudgeNodes.Access) {
            return true;
        }
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            String token =
                    header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            String epoch = request.getHeader("X-Judge-Session-Epoch");
            long session = epoch == null ? 0 : JudgeNodes.parseId(epoch);
            request.setAttribute(ACCESS, nodes.authenticate(token, session));
            return true;
        } catch (JudgeProtocolException e) {
            response.setStatus(e.getStatus());
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), Map.of("code", e.getCode()));
            return false;
        }
    }
}
