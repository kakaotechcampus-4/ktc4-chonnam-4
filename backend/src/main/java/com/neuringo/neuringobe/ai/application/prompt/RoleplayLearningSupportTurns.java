package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Event;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;

/** 저장 순번은 유지하고 판정 불가 입력을 학습 유도 횟수에서만 제외한다. */
public final class RoleplayLearningSupportTurns {
    private RoleplayLearningSupportTurns() {}

    public static OptionalInt nextTurn(UUID goalId, int inputOrdinal, List<Event> history) {
        return count(inputOrdinal, history, event -> event.microGoalId().equals(goalId));
    }

    public static OptionalInt nextSessionTurn(int inputOrdinal, List<Event> history) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).turnNumber() != i + 1) return OptionalInt.empty();
        }
        return count(inputOrdinal, history, event -> true);
    }

    private static OptionalInt count(
            int inputOrdinal, List<Event> history, Predicate<Event> included) {
        if (inputOrdinal < 1) throw new IllegalArgumentException("입력 순번은 양수여야 합니다.");
        var seen = new HashSet<UUID>();
        int inputs = 0, assessable = 0;
        boolean complete = true;
        for (var event : history) {
            if (!seen.add(event.turnId())) throw new IllegalArgumentException("확정 입력 이력이 중복됐습니다.");
            if (!included.test(event)) continue;
            inputs = Math.addExact(inputs, 1);
            if (event.learningState() == null
                    || !RoleplayCodes.LEARNING_STATES.contains(event.learningState())) {
                complete = false;
            } else if (!event.learningState().equals("NOT_ASSESSABLE")) {
                assessable = Math.addExact(assessable, 1);
            }
        }
        if (!complete || inputs != inputOrdinal - 1) return OptionalInt.empty();
        return OptionalInt.of(Math.addExact(assessable, 1));
    }
}
