package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RoleplayUploadDeadlineFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE =
            RoleplayUploadDeadlineFilter.class.getName() + ".deadline";
    private static final Pattern VOICE_PATH =
            Pattern.compile("/api/v1/roleplay-sessions/[^/]+/voice-inputs");
    private final Duration budget;

    public RoleplayUploadDeadlineFilter(Duration budget) {
        this.budget = budget;
    }

    public static RoleplayTurnDeadline deadline(HttpServletRequest request) {
        Object deadline = request.getAttribute(ATTRIBUTE);
        if (!(deadline instanceof RoleplayTurnDeadline value))
            throw new IllegalStateException("음성 요청 처리 기한이 설정되지 않았습니다.");
        return value;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        if ("POST".equals(request.getMethod()) && VOICE_PATH.matcher(path).matches())
            request.setAttribute(ATTRIBUTE, RoleplayTurnDeadline.start(budget));
        chain.doFilter(request, response);
    }
}
