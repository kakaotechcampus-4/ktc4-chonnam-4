package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Outcome;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Status;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 확정 이력과 이번 발화 후보에 같은 연속 도달 규칙을 사용한다. 후보의 저장을 확정하지 않는다. */
public final class RoleplaySupportSequence {
    private RoleplaySupportSequence() {}

    public static Result evaluate(List<Input> inputs, int required, String handling) {
        if (required < 1
                || !("RESET_SEQUENCE".equals(handling) || "KEEP_SEQUENCE".equals(handling)))
            throw new IllegalArgumentException("연속 도달 정책이 올바르지 않습니다.");
        var turns = new HashSet<UUID>();
        int previous = 0;
        for (var input : inputs) {
            if (!turns.add(input.turnId()) || input.turnNumber() <= previous)
                throw new IllegalArgumentException("연속 도달 입력은 중복 없는 시간순이어야 합니다.");
            previous = input.turnNumber();
        }
        var goals = new HashSet<UUID>();
        var basis = new ArrayList<Input>();
        var status = Status.NOT_ELIGIBLE;
        for (int i = inputs.size() - 1; i >= 0; i--) {
            var input = inputs.get(i);
            if (input.outcome() == Outcome.NOT_ASSESSABLE) continue;
            if (input.outcome() == Outcome.UNKNOWN) {
                status = Status.EVIDENCE_UNAVAILABLE;
                break;
            }
            if (input.outcome() == Outcome.IN_PROGRESS && handling.equals("KEEP_SEQUENCE"))
                continue;
            if (input.outcome() != Outcome.ACHIEVED_WITHOUT_HINT) break;
            if (goals.add(input.microGoalId())) basis.add(input);
            if (basis.size() >= required) {
                status = Status.ELIGIBLE_NEXT_GOAL;
                break;
            }
        }
        return new Result(status, basis);
    }

    public record Input(
            @JsonProperty("turn_id") UUID turnId,
            @JsonProperty("micro_goal_id") UUID microGoalId,
            @JsonProperty("turn_number") int turnNumber,
            Outcome outcome,
            List<Reference> basis) {
        public Input {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(microGoalId);
            Objects.requireNonNull(outcome);
            if (turnNumber < 1) throw new IllegalArgumentException("입력 순서가 올바르지 않습니다.");
            basis = List.copyOf(basis);
        }
    }

    public record Result(Status status, List<Input> basis) {
        public Result {
            basis = List.copyOf(basis);
        }
    }
}
