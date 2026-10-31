package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 승인 콘텐츠·명시적 진행 정책·같은 세션의 확정 관찰과 보관 대화를 공급한다. */
public interface RoleplayContextSource {
    Optional<Snapshot> load(Key key, RoleplayTurnDeadline deadline);

    record Key(
            UUID childId,
            UUID activityId,
            UUID goalId,
            UUID sessionId,
            UUID scenarioId,
            int scenarioVersion,
            long checkpointVersion) {
        public Key {
            Objects.requireNonNull(childId);
            Objects.requireNonNull(activityId);
            Objects.requireNonNull(goalId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(scenarioId);
            if (scenarioVersion < 1 || checkpointVersion < 0)
                throw new IllegalArgumentException("Invalid context version");
        }
    }

    /** 승인본과 진행 상태·관찰을 같은 저장 버전의 일관된 자료로 공급한다. */
    record Snapshot(
            Key key,
            String approvalReference,
            RoleplayTurnInput.Scenario scenario,
            RoleplayTurnInput.State state,
            List<RoleplayTurnInput.DialogueLine> recentDialogue,
            GoalObservationInput observations,
            RoleplayGoalResponseHistory responseHistory) {
        public Snapshot {
            Objects.requireNonNull(key);
            if (approvalReference == null || approvalReference.isBlank())
                throw new IllegalArgumentException("Approved scenario reference is required");
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(state);
            recentDialogue = List.copyOf(Objects.requireNonNull(recentDialogue));
            Objects.requireNonNull(observations);
            Objects.requireNonNull(responseHistory);
        }

        @Override
        public String toString() {
            return "Snapshot[key=" + key + "]";
        }
    }
}
