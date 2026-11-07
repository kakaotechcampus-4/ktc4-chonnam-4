package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService.Scope;
import java.util.Objects;
import java.util.UUID;

/** 확정 결과 재조회 뒤, 새 입력을 처리하기 전에 작업자 안에서 컨텍스트를 준비한다. */
public final class RoleplayContextAssembler {
    private final RoleplayContextSource source;

    public RoleplayContextAssembler(RoleplayContextSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public Prepared prepareVoice(
            Scope scope, UUID requestId, UUID turnId, RoleplayTurnDeadline deadline) {
        return prepare(scope, requestId, turnId, deadline);
    }

    public Prepared prepare(
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
        var snapshot =
                deadline.withinBudget(() -> source.load(key, deadline))
                        .orElseThrow(Unavailable::new);
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
        try {
            var input =
                    new RoleplayTurnInput(
                            snapshot.scenario(),
                            state,
                            new RoleplayTurnInput.LearnerTurn(turnId, ""),
                            snapshot.recentDialogue());
            deadline.requireActive();
            snapshot.observations().requireMatches(input);
            return new Prepared(
                    scope.trace(requestId, turnId),
                    input,
                    snapshot.observations(),
                    RoleplayContextFingerprint.of(snapshot));
        } catch (IllegalArgumentException invalidContext) {
            throw new Unavailable();
        }
    }

    /** 입력 정제에서 검증한 표준 발화로 교체하기 전까지 발화를 비워 둔다. */
    public record Prepared(
            AiTraceContext trace,
            RoleplayTurnInput input,
            GoalObservationInput observations,
            String contextFingerprint) {
        public Prepared {
            Objects.requireNonNull(trace);
            Objects.requireNonNull(input);
            Objects.requireNonNull(observations);
            if (contextFingerprint == null || !contextFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("분석 자료의 지문이 필요합니다.");
            }
        }

        @Override
        public String toString() {
            return "Prepared[turnId=" + trace.turnId() + "]";
        }
    }

    /** 승인 자료를 구성할 수 없을 때 원문이나 내부 조회 정보를 노출하지 않는다. */
    public static final class Unavailable extends IllegalStateException {
        private Unavailable() {
            super("승인된 역할극 자료를 확인할 수 없습니다.");
        }
    }
}
