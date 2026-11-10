package com.neuringo.neuringobe.roleplay.controller;

import static com.neuringo.neuringobe.ai.application.observation.RoleplayTiming.Stage.*;

import com.neuringo.neuringobe.ai.application.observation.RoleplayTiming;
import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 입력 원문 없이 HTTP 결과와 비동기 AI 호출을 같은 요청 식별자로 연결한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnExpression(
        "'${neuringo.ai.call-log:false}' == 'true' or '${neuringo.agent-runs.enabled:false}' == 'true'")
public final class RoleplayRequestObservationFilter extends OncePerRequestFilter {
    private static final Logger log =
            LoggerFactory.getLogger(RoleplayRequestObservationFilter.class);
    private static final String RESULT =
            RoleplayRequestObservationFilter.class.getName() + ".result";
    private static final String VERSION = RESULT + ".version";
    private static final String REPLAYED = RESULT + ".replayed";

    private final AiDiagnostics diagnostics;
    private final AgentRunRecorder runs;

    public RoleplayRequestObservationFilter(Optional<AiDiagnostics> diagnostics) {
        this(diagnostics, Optional.empty());
    }

    @Autowired
    public RoleplayRequestObservationFilter(
            Optional<AiDiagnostics> diagnostics, Optional<AgentRunRecorder> runs) {
        this.diagnostics = diagnostics.orElse(AiDiagnostics.NONE);
        this.runs = runs.orElse(AgentRunRecorder.NONE);
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

    /** 실행 기록은 요청 번호(X-Trace-Id)를 실행 번호로 쓴다. 기록 실패는 요청 처리로 전파하지 않는다. */
    private void begin(UUID runId) {
        try {
            runs.begin(runId, AgentRunRecorder.Workflow.ROLEPLAY_TURN);
        } catch (RuntimeException failure) {
            /* 기록을 열지 못해도 역할극 턴은 그대로 처리한다. */
        }
    }

    private void end(
            UUID runId,
            HttpServletRequest request,
            HttpServletResponse response,
            String completion,
            long start) {
        try {
            runs.end(
                    runId,
                    AiDiagnostics.fields(
                            "input",
                            request.getRequestURI().endsWith("/text-inputs") ? "TEXT" : "VOICE",
                            "http_status",
                            response.getStatus(),
                            "completion",
                            completion,
                            "result",
                            request.getAttribute(RESULT),
                            "replayed",
                            request.getAttribute(REPLAYED),
                            "elapsed_ms",
                            (System.nanoTime() - start) / 1_000_000));
        } catch (RuntimeException failure) {
            /* 기록 저장 실패는 응답을 바꾸지 않는다. */
        }
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
        UUID runId = UUID.fromString(id);
        begin(runId);
        var timing = RoleplayTiming.begin(runId, diagnostics, event -> runs.event(runId, event));
        try {
            diagnostics.begin(UUID.fromString(id));
            chain.doFilter(request, response);
            completion = "COMPLETE";
        } finally {
            try {
                timing.finish(
                        "ERROR".equals(completion),
                        request.getAttribute(RESULT),
                        request.getAttribute(REPLAYED));
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
                end(runId, request, response, completion, start);
            } finally {
                if (previous == null) MDC.remove(TraceIds.REQUEST_KEY);
                else MDC.put(TraceIds.REQUEST_KEY, previous);
            }
        }
    }
}
