package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import java.util.Objects;
import java.util.UUID;

/** 검수된 응답과 표준 발화만 전달한다. 원본 음성·STT 원문·실패 후보·사건 정보는 포함하지 않는다. */
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
    private final String responseType;
    private final String strategyUsed;
    private final String responseText;
    private final String canonicalUtterance;
    private final String approvedContextFingerprint;
    private final RoleplayInputContext inputContext;
    private final RoleplayTurnInput.InputQuality inputQuality;

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
        if (!RoleplayCodes.RESPONSE_TYPES.contains(candidate.responseType())
                || !RoleplayCodes.STRATEGY_TYPES.contains(candidate.strategyUsed())) {
            throw new IllegalArgumentException("응답 종류 또는 전략이 허용된 값이 아닙니다.");
        }
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
        this.responseType = candidate.responseType();
        this.strategyUsed = candidate.strategyUsed();
        this.responseText = candidate.text();
        this.canonicalUtterance = result.canonicalUtterance();
        this.approvedContextFingerprint = result.approvedContextFingerprint();
        this.inputContext = result.inputContext();
        this.inputQuality = result.inputQuality();
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

    public String responseType() {
        return responseType;
    }

    public String strategyUsed() {
        return strategyUsed;
    }

    public String canonicalUtterance() {
        return canonicalUtterance;
    }

    public String approvedContextFingerprint() {
        return approvedContextFingerprint;
    }

    public RoleplayInputContext inputContext() {
        return inputContext;
    }

    public RoleplayTurnInput.InputQuality inputQuality() {
        return inputQuality;
    }

    @Override
    public String toString() {
        return "RoleplayCheckpointCommand[content=redacted]";
    }
}
