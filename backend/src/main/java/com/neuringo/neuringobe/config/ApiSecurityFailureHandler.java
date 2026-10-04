package com.neuringo.neuringobe.config;

import com.neuringo.neuringobe.auth.security.InvalidTokenException;
import com.neuringo.neuringobe.common.ApiErrorResponse;
import com.neuringo.neuringobe.common.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
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
        if (exception instanceof InvalidTokenException) {
            write(request, response, 401, "INVALID_TOKEN", "인증 정보가 만료되었거나 올바르지 않습니다.");
        } else {
            write(request, response, 401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
        }
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        // CSRF 실패는 권한 문제가 아니다. 컨트롤러 전에 막혀 아무것도 바뀌지 않았으므로 토큰을 다시 받아 재시도해도 된다.
        if (exception instanceof CsrfException) {
            write(request, response, 403, "CSRF_TOKEN_INVALID", "요청을 확인하지 못했습니다. 다시 시도해 주세요.");
        } else {
            write(request, response, 403, "ACCESS_DENIED", "접근 권한이 없습니다.");
        }
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
        String traceId = TraceIds.newTraceId();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Trace-Id", traceId);
        mapper.writeValue(
                response.getWriter(),
                ApiErrorResponse.of(status, code, message, request.getRequestURI(), traceId));
    }
}
