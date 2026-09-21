package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

public record LlmRequest(
        AiTraceContext traceContext,
        AiAttemptContext attemptContext,
        AiOperation operation,
        int currentAttempt,
        String systemPrompt,
        String userPrompt,
        String promptVersion,
        String responseSchemaVersion,
        String policyVersion) {

    public LlmRequest {
        Objects.requireNonNull(traceContext, "traceContext must not be null");
        Objects.requireNonNull(attemptContext, "attemptContext must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        requireText(systemPrompt, "systemPrompt");
        requireText(userPrompt, "userPrompt");
        requireText(promptVersion, "promptVersion");
        requireText(responseSchemaVersion, "responseSchemaVersion");
        if (currentAttempt < 1) {
            throw new IllegalArgumentException("currentAttempt must be at least 1");
        }
        validateTraceContext(operation, traceContext);
    }

    private static void validateTraceContext(AiOperation operation, AiTraceContext traceContext) {
        switch (operation) {
            case INITIAL_DIFFICULTY_DECISION, NEXT_DIFFICULTY_DECISION ->
                    Objects.requireNonNull(traceContext.childId(), "childId is required");
            case SCENARIO_GENERATION ->
                    Objects.requireNonNull(traceContext.classId(), "classId is required");
            case CAUSE_ANALYSIS -> {
                Objects.requireNonNull(traceContext.sessionId(), "sessionId is required");
                Objects.requireNonNull(traceContext.turnId(), "turnId is required");
            }
            case RESPONSE_GENERATION -> {
                Objects.requireNonNull(traceContext.sessionId(), "sessionId is required");
                Objects.requireNonNull(traceContext.turnId(), "turnId is required");
                Objects.requireNonNull(traceContext.analysisId(), "analysisId is required");
            }
            case RESPONSE_EVALUATION -> {
                Objects.requireNonNull(traceContext.sessionId(), "sessionId is required");
                Objects.requireNonNull(traceContext.turnId(), "turnId is required");
                Objects.requireNonNull(traceContext.analysisId(), "analysisId is required");
                Objects.requireNonNull(traceContext.candidateId(), "candidateId is required");
            }
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
