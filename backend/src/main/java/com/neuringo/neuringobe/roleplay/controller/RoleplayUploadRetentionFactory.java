package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.common.ApiErrorResponse;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.common.TraceIds;
import com.neuringo.neuringobe.roleplay.application.RoleplayTurnResponseMapper;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import com.neuringo.neuringobe.roleplay.service.RoleplayRetentionGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public final class RoleplayUploadRetentionFactory {
    private static final Pattern PATH =
            Pattern.compile("/api/v1/roleplay-sessions/([^/]+)/voice-inputs");
    private final RoleplayRequestScopeService scopes;
    private final RoleplayRetentionGate gate;
    private final RoleplayTurnResponseMapper responses;
    private final RoleplayTurnOutcomeResolver outcomes;
    private final JsonMapper mapper;

    public RoleplayUploadRetentionFactory(
            RoleplayRequestScopeService scopes,
            RoleplayRetentionGate gate,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            JsonMapper mapper) {
        this.scopes = scopes;
        this.gate = gate;
        this.responses = responses;
        this.outcomes = outcomes;
        this.mapper = mapper;
    }

    public OncePerRequestFilter create() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                var match = PATH.matcher(path);
                if (!"POST".equals(request.getMethod()) || !match.matches()) {
                    chain.doFilter(request, response);
                    return;
                }
                UUID sessionId;
                try {
                    sessionId = UUID.fromString(match.group(1));
                } catch (IllegalArgumentException invalid) {
                    chain.doFilter(request, response);
                    return;
                }
                try {
                    scopes.loadForChildSession(
                            SecurityContextHolder.getContext().getAuthentication(), sessionId);
                } catch (ApiException error) {
                    response.setStatus(error.getStatus().value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                    mapper.writeValue(
                            response.getOutputStream(),
                            ApiErrorResponse.of(
                                    error.getStatus().value(),
                                    error.getCode(),
                                    error.getMessage(),
                                    request.getRequestURI(),
                                    TraceIds.newTraceId()));
                    return;
                }
                try (var ignored =
                        gate.enterInput(
                                sessionId, RoleplayUploadDeadlineFilter.deadline(request))) {
                    chain.doFilter(request, response);
                } catch (RoleplayTurnDeadline.Expired expired) {
                    if (response.isCommitted()) throw expired;
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                    mapper.writeValue(
                            response.getOutputStream(),
                            ApiResponse.of(
                                    responses.map(
                                            new RoleplayCheckpointedTurnExecutor.Recovery(
                                                    outcomes.resolve(
                                                            new RoleplayTurnResult.TimedOut())))));
                }
            }
        };
    }
}
