package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

public record AnalysisResult(
        @JsonProperty("turn_id") UUID turnId,
        @JsonProperty("response_act") String responseAct,
        String relevance,
        @JsonProperty("learning_state") String learningState,
        @JsonProperty("primary_gap") PrimaryGap primaryGap,
        @JsonProperty("next_strategy") NextStrategy nextStrategy,
        @JsonProperty("analysis_confidence") Double analysisConfidence) {

    public AnalysisResult {
        Objects.requireNonNull(turnId, "turnId must not be null");
        requireText(learningState, "learningState");
        Objects.requireNonNull(primaryGap, "primaryGap must not be null");
        Objects.requireNonNull(nextStrategy, "nextStrategy must not be null");
        Objects.requireNonNull(analysisConfidence, "analysisConfidence must not be null");
    }

    public record PrimaryGap(String code, String description) {

        public PrimaryGap {
            requireText(code, "primaryGap.code");
        }
    }

    public record NextStrategy(
            String type,
            @JsonProperty("target_micro_goal_id") UUID targetMicroGoalId,
            String purpose,
            @JsonProperty("recommended_support_level") String recommendedSupportLevel) {

        public NextStrategy {
            requireText(type, "nextStrategy.type");
            Objects.requireNonNull(
                    targetMicroGoalId, "nextStrategy.targetMicroGoalId must not be null");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
