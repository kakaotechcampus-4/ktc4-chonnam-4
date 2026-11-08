package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.common.TraceIds;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 입력 원문 없이 HTTP 결과와 비동기 AI 호출을 같은 요청 식별자로 연결한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name = "neuringo.ai.call-log", havingValue = "true")
public final class RoleplayRequestObservationFilter extends OncePerRequestFilter {
    private static final Logger log =
            LoggerFactory.getLogger(RoleplayRequestObservationFilter.class);
    private static final String RESULT =
            RoleplayRequestObservationFilter.class.getName() + ".result";
    private static final String VERSION = RESULT + ".version";
    private static final String REPLAYED = RESULT + ".replayed";

    private final AiDiagnostics diagnostics;

    public RoleplayRequestObservationFilter(Optional<AiDiagnostics> diagnostics) {
        this.diagnostics = diagnostics.orElse(AiDiagnostics.NONE);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !request.getRequestURI()
                        .matches(
                                request.getContextPath()
                                        + "/api/v1/roleplay-sessions/[^/]+/(text-inputs|voice-inputs)");
    }

    public static void recordResult(HttpServletRequest request, RoleplayTurnResponse response) {
        request.setAttribute(RESULT, response.status().name());
        request.setAttribute(VERSION, response.checkpointVersion());
        request.setAttribute(REPLAYED, response.replayed());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get(TraceIds.REQUEST_KEY);
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();
        String completion = "ERROR";
        MDC.put(TraceIds.REQUEST_KEY, id);
        response.setHeader("X-Trace-Id", id);
        try {
            diagnostics.begin(UUID.fromString(id));
            chain.doFilter(request, response);
            completion = "COMPLETE";
        } finally {
            try {
                diagnostics.end(
                        UUID.fromString(id),
                        AiDiagnostics.fields(
                                "input",
                                request.getRequestURI().endsWith("/text-inputs") ? "TEXT" : "VOICE",
                                "http_status",
                                response.getStatus(),
                                "completion",
                                completion,
                                "result",
                                request.getAttribute(RESULT),
                                "checkpoint_version",
                                request.getAttribute(VERSION),
                                "replayed",
                                request.getAttribute(REPLAYED),
                                "elapsed_ms",
                                (System.nanoTime() - start) / 1_000_000));
                log.info(
                        "ROLEPLAY_REQUEST request={} input={} http_status={} completion={} result={} checkpoint={} replayed={} ms={}",
                        id,
                        request.getRequestURI().endsWith("/text-inputs") ? "TEXT" : "VOICE",
                        response.getStatus(),
                        completion,
                        request.getAttribute(RESULT),
                        request.getAttribute(VERSION),
                        request.getAttribute(REPLAYED),
                        (System.nanoTime() - start) / 1_000_000);
            } finally {
                if (previous == null) MDC.remove(TraceIds.REQUEST_KEY);
                else MDC.put(TraceIds.REQUEST_KEY, previous);
            }
        }
    }
}
