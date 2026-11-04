package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.Objects;
import java.util.UUID;

/** 세션의 음성 실패 누적과 권유 상태를 읽으며 실패를 기록하지 않는다. */
public interface RoleplayVoiceRecoverySource {
    default boolean textRecommended(Key key, RoleplayTurnDeadline deadline) {
        return progress(key, deadline).textRecommended();
    }

    RoleplayVoiceFailureProgress progress(Key key, RoleplayTurnDeadline deadline);

    record Key(
            UUID childId, UUID activityId, UUID sessionId, UUID scenarioId, int scenarioVersion) {
        public Key {
            Objects.requireNonNull(childId);
            Objects.requireNonNull(activityId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(scenarioId);
            if (scenarioVersion < 1) throw new IllegalArgumentException("시나리오 버전은 양수여야 합니다.");
        }
    }
}
