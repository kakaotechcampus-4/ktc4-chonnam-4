package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import java.util.Objects;
import java.util.UUID;

/** Identity from authenticated server context; fingerprint is server-computed, not learner text. */
public record RoleplayRequestIdentity(
        AiTraceContext trace,
        UUID idempotencyKey,
        String fingerprint,
        long expectedVersion,
        int turnNumber) {
    public RoleplayRequestIdentity {
        Objects.requireNonNull(trace);
        Objects.requireNonNull(trace.sessionId());
        Objects.requireNonNull(trace.childId());
        Objects.requireNonNull(trace.scenarioId());
        Objects.requireNonNull(idempotencyKey);
        if (trace.scenarioVersion() == null
                || trace.scenarioVersion() < 1
                || expectedVersion < 0
                || turnNumber < 1
                || fingerprint == null
                || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid roleplay request identity");
        }
    }

    @Override
    public String toString() {
        return "RoleplayRequestIdentity[content=redacted]";
    }
}
