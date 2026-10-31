package com.neuringo.neuringobe.roleplay.dto;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.roleplay.domain.RoleplaySession;
import java.util.UUID;

/** 시작·복원 트랜잭션에서 확인한 상태이며 공개 시작 메시지 응답과는 구분한다. */
public record RoleplaySessionState(
        UUID sessionId,
        UUID activityId,
        UUID scenarioId,
        int scenarioVersion,
        String status,
        ActivityStatus activityStatus,
        String currentSupportLevel,
        UUID currentMicroGoalId,
        int lastTurnNumber,
        UUID lastTurnId,
        long checkpointVersion) {
    public static RoleplaySessionState from(
            RoleplaySession session, ActivityStatus activityStatus) {
        return new RoleplaySessionState(
                session.getSessionId(),
                session.getActivityId(),
                session.getScenarioId(),
                session.getScenarioVersion(),
                session.getStatus(),
                activityStatus,
                session.getCurrentSupportLevel(),
                session.getCurrentMicroGoalId(),
                session.getLastTurnNumber(),
                session.getLastTurnId(),
                session.getRowVersion());
    }
}
