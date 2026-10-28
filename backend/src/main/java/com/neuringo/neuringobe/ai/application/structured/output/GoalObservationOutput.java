package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 목표 관찰의 내부 출력 계약이다. 활동 완료나 DB 저장을 직접 수행하지 않는다. */
public final class GoalObservationOutput {
    public static final String ANALYSIS_VERSION = "analysis-result/v2";
    public static final String EVALUATION_VERSION = "evaluation-result/v2";

    private GoalObservationOutput() {}

    public record Reference(
            @JsonProperty("turn_id") UUID turnId,
            @JsonProperty("micro_goal_id") UUID microGoalId,
            @JsonProperty("criterion_reference") String criterionReference) {
        public Reference {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(microGoalId);
            if (criterionReference == null || criterionReference.isBlank()) {
                throw new IllegalArgumentException("학습 증거 참조가 필요합니다.");
            }
        }
    }

    public enum Status {
        EXPLICIT,
        IMPLICIT,
        NOT_EXPRESSED,
        MISUNDERSTOOD,
        CONTRADICTORY
    }

    public record Observation(Reference reference, Status status) {
        public Observation {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(status);
        }
    }

    public enum ActivityDecision {
        ACHIEVED,
        IN_PROGRESS,
        UNAVAILABLE
    }

    public record Assessment(ActivityDecision decision, List<Reference> basis) {
        public Assessment {
            Objects.requireNonNull(decision);
            basis = List.copyOf(basis);
        }
    }

    public record Analysis(
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("analysis_id") UUID analysisId,
            List<Observation> observations,
            @JsonProperty("activity_goal") Assessment activityGoal) {
        public Analysis {
            if (!ANALYSIS_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("목표 관찰 분석 버전이 일치하지 않습니다.");
            }
            Objects.requireNonNull(analysisId);
            observations = List.copyOf(observations);
            Objects.requireNonNull(activityGoal);
        }
    }

    public record Review(
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("analysis_id") UUID analysisId,
            @JsonProperty("observations_supported") Boolean observationsSupported,
            @JsonProperty("activity_goal_supported") Boolean activityGoalSupported) {
        public Review {
            if (!EVALUATION_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("목표 관찰 검수 버전이 일치하지 않습니다.");
            }
            Objects.requireNonNull(analysisId);
            Objects.requireNonNull(observationsSupported);
            Objects.requireNonNull(activityGoalSupported);
        }

        public boolean supportsAnalysis() {
            return observationsSupported && activityGoalSupported;
        }
    }
}
