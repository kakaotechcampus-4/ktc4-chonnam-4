package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 같은 저장 버전의 목표·지원 정책을 공급한다. 유도 턴·질문 횟수·정답 제공 여부는 확정 이력에서 읽는다. */
public interface RoleplayProgressSource {
    Optional<Progress> load(RoleplayContextSource.Key key, RoleplayTurnDeadline deadline);

    record Progress(
            UUID currentMicroGoalId,
            String currentSupportLevel,
            int turnCount,
            int maximumTurnCount,
            int maximumSupportTurnsPerMicroGoal,
            Map<UUID, String> microGoalStatuses) {
        public Progress {
            Objects.requireNonNull(currentMicroGoalId);
            if (currentSupportLevel == null
                    || !RoleplayCodes.SUPPORT_LEVELS.contains(currentSupportLevel)
                    || turnCount < 1
                    || maximumTurnCount < 1
                    || maximumSupportTurnsPerMicroGoal < 1) {
                throw new IllegalArgumentException("목표 진행·지원 정책이 올바르지 않습니다.");
            }
            microGoalStatuses = Map.copyOf(microGoalStatuses);
        }
    }
}
