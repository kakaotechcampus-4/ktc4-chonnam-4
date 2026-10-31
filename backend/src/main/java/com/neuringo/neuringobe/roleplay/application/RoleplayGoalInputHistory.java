package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** 질문을 받은 목표별 확정 입력이다. 관찰된 다른 목표의 시도·도달 여부로 확대하지 않는다. */
public record RoleplayGoalInputHistory(Map<UUID, GoalInputs> byGoal, boolean complete) {
    public RoleplayGoalInputHistory {
        byGoal = Map.copyOf(byGoal);
    }

    public static RoleplayGoalInputHistory restore(
            Set<UUID> approvedGoals, List<RoleplayGoalResponseHistory.Response> responses) {
        var grouped = new HashMap<UUID, List<Attempt>>();
        approvedGoals.forEach(g -> grouped.put(g, new ArrayList<>()));
        var seenTurns = new HashSet<UUID>();
        boolean complete = true;
        for (int i = 0; i < responses.size(); i++) {
            var response = responses.get(i);
            if (response.turnNumber() != i + 1 || !seenTurns.add(response.turnId()))
                throw new IllegalArgumentException("확정 입력 턴이 누락되거나 중복됐습니다.");
            var input = response.inputContext();
            if (input == null) {
                complete = false;
                continue;
            }
            var attempts = grouped.get(input.microGoalId());
            if (attempts == null) throw new IllegalArgumentException("입력 당시 목표가 승인 범위에 없습니다.");
            if (complete && input.supportTurn() != Math.addExact(attempts.size(), 1))
                throw new IllegalArgumentException("저장된 유도 턴 번호와 확정 입력 횟수가 다릅니다.");
            attempts.add(
                    new Attempt(
                            response.turnId(),
                            response.candidateId(),
                            response.turnNumber(),
                            response.checkpointVersion(),
                            input));
        }
        var result = new HashMap<UUID, GoalInputs>();
        grouped.forEach((goal, attempts) -> result.put(goal, new GoalInputs(attempts)));
        return new RoleplayGoalInputHistory(result, complete);
    }

    public OptionalInt exactAttemptCount(UUID goalId) {
        var inputs = byGoal.get(goalId);
        if (inputs == null) throw new IllegalArgumentException("승인된 목표가 아닙니다.");
        return complete ? OptionalInt.of(inputs.knownAttempts().size()) : OptionalInt.empty();
    }

    public OptionalInt nextSupportTurn(UUID goalId) {
        var count = exactAttemptCount(goalId);
        return count.isPresent()
                ? OptionalInt.of(Math.addExact(count.getAsInt(), 1))
                : OptionalInt.empty();
    }

    public record GoalInputs(List<Attempt> knownAttempts) {
        public GoalInputs {
            knownAttempts = List.copyOf(knownAttempts);
        }
    }

    public record Attempt(
            UUID turnId,
            UUID candidateId,
            int turnNumber,
            long checkpointVersion,
            RoleplayInputContext inputContext) {
        public Attempt {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(candidateId);
            Objects.requireNonNull(inputContext);
        }
    }
}
