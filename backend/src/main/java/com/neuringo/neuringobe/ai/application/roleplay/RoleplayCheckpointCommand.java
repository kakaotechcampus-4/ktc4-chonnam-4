package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import java.util.Objects;
import java.util.UUID;

/**
 * Approved data only. No source audio, STT original, generated audio, rejected candidate or event.
 */
public final class RoleplayCheckpointCommand {
    private final UUID requestId;
    private final UUID idempotencyKey;
    private final String inputFingerprint;
    private final UUID activityId;
    private final UUID childId;
    private final UUID scenarioId;
    private final int scenarioVersion;
    private final UUID sessionId;
    private final UUID turnId;
    private final UUID candidateId;
    private final UUID microGoalId;
    private final long expectedVersion;
    private final int turnNumber;
    private final String supportLevel;
    private final String responseText;
    private final String canonicalUtterance;

    private RoleplayCheckpointCommand(
            AiTraceContext trace,
            long expectedVersion,
            int turnNumber,
            UUID idempotencyKey,
            String inputFingerprint,
            RoleplayTurnResult.SpokenReady result) {
        Objects.requireNonNull(trace);
        Objects.requireNonNull(result);
        Objects.requireNonNull(trace.sessionId());
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.childId = Objects.requireNonNull(trace.childId());
        this.activityId = trace.activityId();
        this.scenarioId = Objects.requireNonNull(trace.scenarioId());
        if (trace.scenarioVersion() == null || trace.scenarioVersion() < 1)
            throw new IllegalArgumentException("Approved scenario version required");
        this.scenarioVersion = trace.scenarioVersion();
        if (inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected a SHA-256 input fingerprint");
        this.inputFingerprint = inputFingerprint;
        if (expectedVersion < 0 || turnNumber < 1)
            throw new IllegalArgumentException("Invalid checkpoint counters");
        var candidate = result.ready().candidate();
        if (!candidate.turnId().equals(trace.turnId()))
            throw new IllegalArgumentException("Checkpoint belongs to another turn");
        this.requestId = trace.requestId();
        this.sessionId = trace.sessionId();
        this.turnId = candidate.turnId();
        this.candidateId = candidate.candidateId();
        this.microGoalId = candidate.targetMicroGoalId();
        this.expectedVersion = expectedVersion;
        this.turnNumber = turnNumber;
        this.supportLevel = candidate.supportLevel();
        this.responseText = candidate.text();
        this.canonicalUtterance = result.canonicalUtterance();
    }

    public static RoleplayCheckpointCommand fromApproved(
            AiTraceContext trace,
            long expectedVersion,
            int turnNumber,
            UUID idempotencyKey,
            String inputFingerprint,
            RoleplayTurnResult.SpokenReady result) {
        return new RoleplayCheckpointCommand(
                trace, expectedVersion, turnNumber, idempotencyKey, inputFingerprint, result);
    }

    public UUID idempotencyKey() {
        return idempotencyKey;
    }

    public String inputFingerprint() {
        return inputFingerprint;
    }

    public UUID activityId() {
        return activityId;
    }

    public UUID childId() {
        return childId;
    }

    public UUID scenarioId() {
        return scenarioId;
    }

    public int scenarioVersion() {
        return scenarioVersion;
    }

    public UUID requestId() {
        return requestId;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID turnId() {
        return turnId;
    }

    public UUID candidateId() {
        return candidateId;
    }

    public UUID microGoalId() {
        return microGoalId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public int turnNumber() {
        return turnNumber;
    }

    public String supportLevel() {
        return supportLevel;
    }

    public String responseText() {
        return responseText;
    }

    public String canonicalUtterance() {
        return canonicalUtterance;
    }

    @Override
    public String toString() {
        return "RoleplayCheckpointCommand[content=redacted]";
    }
}
