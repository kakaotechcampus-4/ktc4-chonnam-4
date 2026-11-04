package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.*;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportSequence;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 명시적인 정책으로만 서로 다른 목표의 연속 독립 도달 자격을 계산한다. */
public final class RoleplaySupportAdjustmentPolicy {
    public static final String POLICY_VERSION_V1 = "SUPPORT_DECREASE_V1";

    private RoleplaySupportAdjustmentPolicy() {}

    public static RoleplaySupportAdjustmentContext evaluate(List<Event> history, Rules rules) {
        history = List.copyOf(history);
        var turns = new HashSet<UUID>();
        int previousTurn = 0;
        long previousVersion = 0;
        for (var event : history) {
            if (!turns.add(event.turnId())
                    || event.turnNumber() <= previousTurn
                    || event.checkpointVersion() <= previousVersion)
                throw new IllegalArgumentException("지원 정책에는 중복 없는 시간순 확정 이력이 필요합니다.");
            previousTurn = event.turnNumber();
            previousVersion = event.checkpointVersion();
        }
        if (rules == null)
            return new RoleplaySupportAdjustmentContext(
                    Status.POLICY_REQUIRED, null, null, null, null, List.of(), history);
        var sequence =
                RoleplaySupportSequence.evaluate(
                        history.stream()
                                .map(
                                        e ->
                                                new RoleplaySupportSequence.Input(
                                                        e.turnId(),
                                                        e.microGoalId(),
                                                        e.turnNumber(),
                                                        e.outcome(),
                                                        e.basis()))
                                .toList(),
                        rules.requiredIndependentGoals(),
                        rules.inProgressHandling().name());
        var byTurn =
                history.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Event::turnId, java.util.function.Function.identity()));
        var basis = sequence.basis().stream().map(e -> byTurn.get(e.turnId())).toList();
        return new RoleplaySupportAdjustmentContext(
                sequence.status(),
                rules.policyVersion(),
                rules.requiredIndependentGoals(),
                rules.inProgressHandling().name(),
                basis.size(),
                basis,
                history);
    }

    public enum InProgressHandling {
        RESET_SEQUENCE,
        KEEP_SEQUENCE
    }

    public record Rules(
            int requiredIndependentGoals,
            InProgressHandling inProgressHandling,
            String policyVersion) {
        public Rules {
            if (requiredIndependentGoals < 1
                    || inProgressHandling == null
                    || policyVersion == null
                    || policyVersion.isBlank())
                throw new IllegalArgumentException("지원 감소의 목표 수·진행 중 처리·정책 버전이 필요합니다.");
            if (POLICY_VERSION_V1.equals(policyVersion)
                    && (requiredIndependentGoals != 2
                            || inProgressHandling != InProgressHandling.RESET_SEQUENCE))
                throw new IllegalArgumentException(
                        "SUPPORT_DECREASE_V1은 목표 2개·진행 중 연속성 초기화 정책입니다. 기준 변경 시 정책 버전도 변경해야 합니다.");
        }
    }
}
