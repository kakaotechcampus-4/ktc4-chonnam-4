package com.neuringo.neuringobe.config;

import com.neuringo.neuringobe.common.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Security-filter failures must use the same API envelope as controller failures. */
@Component
public class ApiSecurityFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;

    public ApiSecurityFailureHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        write(request, response, 401, "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        write(request, response, 403, "ACCESS_DENIED", "접근 권한이 없습니다.");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String traceId = UUID.randomUUID().toString();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Trace-Id", traceId);
        mapper.writeValue(
                response.getWriter(),
                ApiErrorResponse.of(status, code, message, request.getRequestURI(), traceId));
    }
}
