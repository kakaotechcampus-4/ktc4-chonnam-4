package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 저장된 질문 초점·S·턴을 유지하며 목표 상태·명시적 제한 정책을 공급한다. */
public interface RoleplayProgressPolicy {
    Optional<Selection> select(Stored stored, RoleplayTurnDeadline deadline);

    record Stored(
            RoleplayContextSource.Key key,
            UUID currentMicroGoalId,
            String currentSupportLevel,
            int turnCount,
            Set<UUID> approvedMicroGoalIds) {
        public Stored {
            Objects.requireNonNull(key);
            if (currentSupportLevel == null
                    || !RoleplayCodes.SUPPORT_LEVELS.contains(currentSupportLevel)
                    || turnCount < 1) {
                throw new IllegalArgumentException("저장된 지원 수준·턴이 올바르지 않습니다.");
            }
            approvedMicroGoalIds = Set.copyOf(approvedMicroGoalIds);
        }
    }

    record Selection(
            UUID currentMicroGoalId,
            int maximumTurnCount,
            int maximumSupportTurnsPerMicroGoal,
            Map<UUID, String> microGoalStatuses) {
        public Selection {
            Objects.requireNonNull(currentMicroGoalId);
            if (maximumTurnCount < 1 || maximumSupportTurnsPerMicroGoal < 1) {
                throw new IllegalArgumentException("명시적인 턴 제한 정책이 필요합니다.");
            }
            microGoalStatuses = Map.copyOf(microGoalStatuses);
            if (!microGoalStatuses.values().stream()
                    .allMatch(RoleplayCodes.MICRO_GOAL_STATUSES::contains)) {
                throw new IllegalArgumentException("작은 목표 상태가 올바르지 않습니다.");
            }
        }
    }
}
