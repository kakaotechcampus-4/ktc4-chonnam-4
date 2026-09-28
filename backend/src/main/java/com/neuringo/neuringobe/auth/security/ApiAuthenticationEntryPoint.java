package com.neuringo.neuringobe.auth.security;

import com.neuringo.neuringobe.common.ApiErrorResponse;
import com.neuringo.neuringobe.common.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * Security 필터 단계의 401 을 공통 오류 형식으로 응답한다. 필터 단계 예외는 {@code GlobalExceptionHandler} 에 닿지 않으므로 따로 둔다.
 *
 * <ul>
 *   <li>{@link InvalidTokenException}: {@link BearerTokenFilter} 가 직접 넘긴 경우 → {@code INVALID_TOKEN}
 *   <li>그 밖: 헤더 없이 보호 경로에 온 요청을 {@code AuthorizationFilter} 가 막은 경우 → {@code
 *       AUTHENTICATION_REQUIRED}
 * </ul>
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        boolean invalidToken = authException instanceof InvalidTokenException;
        String code = invalidToken ? "INVALID_TOKEN" : "AUTHENTICATION_REQUIRED";
        String message = invalidToken ? "인증 정보가 만료되었거나 올바르지 않습니다." : "로그인이 필요합니다.";

        ApiErrorResponse body =
                ApiErrorResponse.of(
                        HttpStatus.UNAUTHORIZED.value(),
                        code,
                        message,
                        request.getRequestURI(),
                        TraceIds.newTraceId());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
