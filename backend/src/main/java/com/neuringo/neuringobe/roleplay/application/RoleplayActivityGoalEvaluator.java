package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;

/** 검수 결과와 같은 서버 자료를 확인해 변경안을 준비한다. 임의 달성 판정은 만들지 않는다. */
public interface RoleplayActivityGoalEvaluator {
    RoleplayGoalObservationPlan evaluate(
            RoleplayCheckpointCommand command,
            RoleplayTurnResult.Ready approvedTurn,
            RoleplayTurnDeadline deadline);

    enum Decision {
        ACHIEVED,
        IN_PROGRESS,
        UNAVAILABLE
    }

    final class Unavailable extends IllegalStateException {
        public Unavailable() {
            super("활동 목표의 진행 상태를 확인할 수 없습니다.");
        }
    }
}
