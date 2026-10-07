package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService.Scope;
import java.util.Objects;
import java.util.UUID;

/**
 * Invoke inside the bounded worker after confirmed replay lookup, before starting new voice work.
 */
public final class RoleplayContextAssembler {
    private final RoleplayContextSource source;

    public RoleplayContextAssembler(RoleplayContextSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public Prepared prepareVoice(
            Scope scope, UUID requestId, UUID turnId, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(turnId);
        Objects.requireNonNull(deadline).requireActive();
        if (!scope.acceptsNewTurn()) throw new Unavailable();
        var key =
                new RoleplayContextSource.Key(
                        scope.childId(),
                        scope.activityId(),
                        scope.goalId(),
                        scope.sessionId(),
                        scope.scenarioId(),
                        scope.scenarioVersion(),
                        scope.checkpointVersion());
        var snapshot = deadline.withinBudget(() -> source.load(key)).orElseThrow(Unavailable::new);
        var state = snapshot.state();
        if (!key.equals(snapshot.key())
                || state.turnCount() != Math.addExact(scope.lastTurnNumber(), 1)
                || (scope.lastTurnNumber() > 0
                        && (scope.currentMicroGoalId() == null
                                || scope.currentSupportLevel() == null))
                || (scope.currentMicroGoalId() != null
                        && !scope.currentMicroGoalId().equals(state.currentMicroGoalId()))
                || (scope.currentSupportLevel() != null
                        && !scope.currentSupportLevel().equals(state.currentSupportLevel()))) {
            throw new Unavailable();
        }
        var input =
                new RoleplayTurnInput(
                        snapshot.scenario(),
                        state,
                        new RoleplayTurnInput.LearnerTurn(turnId, ""),
                        snapshot.recentDialogue());
        deadline.requireActive();
        return new Prepared(scope.trace(requestId, turnId), input);
    }

    /**
     * Learner text is empty until the speech pipeline replaces it with validated canonical text.
     */
    public record Prepared(AiTraceContext trace, RoleplayTurnInput input) {
        public Prepared {
            Objects.requireNonNull(trace);
            Objects.requireNonNull(input);
        }

        @Override
        public String toString() {
            return "Prepared[turnId=" + trace.turnId() + "]";
        }
    }

    /** Internal failure; no fallback content, user utterance or source details attached. */
    public static final class Unavailable extends IllegalStateException {
        private Unavailable() {
            super("Approved roleplay context is unavailable or inconsistent");
        }
    }
}
