package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;
import java.util.UUID;

public record AiTraceContext(
        UUID requestId,
        UUID activityId,
        UUID classId,
        UUID childId,
        UUID scenarioId,
        Integer scenarioVersion,
        UUID sessionId,
        UUID turnId,
        UUID analysisId,
        UUID candidateId) {

    public AiTraceContext {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(activityId, "activityId must not be null");
    }
}
