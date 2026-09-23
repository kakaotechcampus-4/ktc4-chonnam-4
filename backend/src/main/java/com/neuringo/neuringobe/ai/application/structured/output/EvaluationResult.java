package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EvaluationResult(
        @JsonProperty("candidate_id") UUID candidateId,
        @JsonProperty("safe_to_send") Boolean safeToSend,
        EvaluationDecision decision,
        @JsonProperty("retry_target") RetryTarget retryTarget,
        @JsonProperty("critical_failure_count") Integer criticalFailureCount,
        @JsonProperty("failure_codes") List<String> failureCodes,
        @JsonProperty("revision_instruction") RevisionInstruction revisionInstruction) {

    public EvaluationResult {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(safeToSend, "safeToSend must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(criticalFailureCount, "criticalFailureCount must not be null");
        Objects.requireNonNull(failureCodes, "failureCodes must not be null");
        failureCodes = List.copyOf(failureCodes);
        if (criticalFailureCount < 0) {
            throw new IllegalArgumentException("criticalFailureCount must not be negative");
        }
        if (decision != EvaluationDecision.PASS && retryTarget == null) {
            throw new IllegalArgumentException("retryTarget is required for a failed evaluation");
        }
        if ((decision == EvaluationDecision.REGENERATE
                        || decision == EvaluationDecision.SAFETY_REGENERATE)
                && revisionInstruction == null) {
            throw new IllegalArgumentException(
                    "revisionInstruction is required when regeneration is requested");
        }
    }

    public boolean canDeliver() {
        return Boolean.TRUE.equals(safeToSend)
                && decision == EvaluationDecision.PASS
                && criticalFailureCount == 0;
    }
}
