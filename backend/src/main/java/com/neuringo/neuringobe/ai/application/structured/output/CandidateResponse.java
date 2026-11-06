package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CandidateResponse(
        @JsonProperty("candidate_id") UUID candidateId,
        @JsonProperty("turn_id") UUID turnId,
        String text,
        @JsonProperty("response_type") String responseType,
        @JsonProperty("strategy_used") String strategyUsed,
        @JsonProperty("target_micro_goal_id") UUID targetMicroGoalId,
        @JsonProperty("support_level") String supportLevel,
        @JsonProperty("previous_failed_candidate_ids") List<UUID> previousFailedCandidateIds,
        @JsonProperty("mascot_hint") String mascotHint) {

    /** 마스코트 힌트가 없는 후보. */
    public CandidateResponse(
            UUID candidateId,
            UUID turnId,
            String text,
            String responseType,
            String strategyUsed,
            UUID targetMicroGoalId,
            String supportLevel,
            List<UUID> previousFailedCandidateIds) {
        this(
                candidateId,
                turnId,
                text,
                responseType,
                strategyUsed,
                targetMicroGoalId,
                supportLevel,
                previousFailedCandidateIds,
                null);
    }

    public CandidateResponse {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(targetMicroGoalId, "targetMicroGoalId must not be null");
        requireText(text, "text");
        requireText(responseType, "responseType");
        requireText(strategyUsed, "strategyUsed");
        requireText(supportLevel, "supportLevel");
        previousFailedCandidateIds =
                previousFailedCandidateIds == null
                        ? List.of()
                        : List.copyOf(previousFailedCandidateIds);
        // 모델이 힌트가 없다는 뜻으로 "null" 문자열을 쓰는 경우도 없는 것으로 본다.
        mascotHint =
                mascotHint == null
                                || mascotHint.isBlank()
                                || mascotHint.strip().equalsIgnoreCase("null")
                        ? null
                        : mascotHint.strip();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
