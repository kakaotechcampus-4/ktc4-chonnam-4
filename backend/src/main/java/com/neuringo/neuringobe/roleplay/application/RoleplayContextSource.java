package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted server source of approved scenario content, progress, explicit policy and retained
 * dialogue. No default implementation.
 */
public interface RoleplayContextSource {
    Optional<Snapshot> load(Key key);

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

    /** Source must resolve the approved version and supply one coherent checkpoint snapshot. */
    record Snapshot(
            Key key,
            String approvalReference,
            RoleplayTurnInput.Scenario scenario,
            RoleplayTurnInput.State state,
            List<RoleplayTurnInput.DialogueLine> recentDialogue) {
        public Snapshot {
            Objects.requireNonNull(key);
            if (approvalReference == null || approvalReference.isBlank())
                throw new IllegalArgumentException("Approved scenario reference is required");
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(state);
            recentDialogue = List.copyOf(Objects.requireNonNull(recentDialogue));
        }

        @Override
        public String toString() {
            return "Snapshot[key=" + key + "]";
        }
    }
}
