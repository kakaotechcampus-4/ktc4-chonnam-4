package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 확정 이력에 대한 지원 감소 자격이다. 지원 변경 명령이나 활동 완료 판정은 아니다. */
public record RoleplaySupportAdjustmentContext(
        Status status,
        @JsonProperty("policy_version") String policyVersion,
        @JsonProperty("required_independent_goals") Integer requiredIndependentGoals,
        @JsonProperty("in_progress_handling") String inProgressHandling,
        @JsonProperty("independent_goal_count") Integer independentGoalCount,
        List<Event> basis,
        @JsonProperty("confirmed_inputs") List<Event> confirmedInputs) {
    public RoleplaySupportAdjustmentContext {
        Objects.requireNonNull(status);
        basis = List.copyOf(basis);
        confirmedInputs = List.copyOf(confirmedInputs);
        if (status == Status.ELIGIBLE_NEXT_GOAL
                && (policyVersion == null
                        || policyVersion.isBlank()
                        || requiredIndependentGoals == null
                        || requiredIndependentGoals < 1
                        || independentGoalCount == null
                        || independentGoalCount < requiredIndependentGoals
                        || basis.size() != independentGoalCount))
            throw new IllegalArgumentException("지원 감소 자격에는 명시적 정책과 서로 다른 목표의 근거가 필요합니다.");
        if (basis.stream().map(Event::microGoalId).distinct().count() != basis.size()
                || basis.stream().anyMatch(e -> e.outcome() != Outcome.ACHIEVED_WITHOUT_HINT)
                || !confirmedInputs.containsAll(basis))
            throw new IllegalArgumentException("지원 감소 근거는 확정된 서로 다른 목표의 힌트 없는 도달이어야 합니다.");
    }

    public static RoleplaySupportAdjustmentContext unavailable() {
        return new RoleplaySupportAdjustmentContext(
                Status.EVIDENCE_UNAVAILABLE, null, null, null, null, List.of(), List.of());
    }

    public enum Status {
        POLICY_REQUIRED,
        EVIDENCE_UNAVAILABLE,
        NOT_ELIGIBLE,
        ELIGIBLE_NEXT_GOAL
    }

    public enum Outcome {
        UNKNOWN,
        ACHIEVED_WITHOUT_HINT,
        ACHIEVED_WITH_SUPPORT,
        SUPPORTED_NOT_ACHIEVED,
        IN_PROGRESS,
        NOT_ASSESSABLE
    }

    public record Event(
            @JsonProperty("turn_id") UUID turnId,
            @JsonProperty("micro_goal_id") UUID microGoalId,
            @JsonProperty("turn_number") int turnNumber,
            @JsonProperty("checkpoint_version") long checkpointVersion,
            Outcome outcome,
            @JsonProperty("learning_state") String learningState,
            List<Reference> basis) {
        public Event {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(microGoalId);
            Objects.requireNonNull(outcome);
            basis = List.copyOf(basis);
            if (turnNumber < 1 || checkpointVersion < 1)
                throw new IllegalArgumentException("지원 이력은 확정 입력을 참조해야 합니다.");
        }
    }
}
