package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.Set;

/** 확정 도움 이력과 충돌하는 추가 유도·지원 되돌리기를 차단한다. */
public final class RoleplaySupportDecisionRules {
    private static final Set<String> GOAL_QUESTION_STRATEGIES =
            Set.of("PROBE_EVENT", "PROBE_EMOTION", "PROBE_CAUSE", "PROBE_ACTION", "ADVANCE_GOAL");

    private RoleplaySupportDecisionRules() {}

    public static void requireValidAnalysis(
            RoleplayTurnInput.State state,
            GoalObservationInput observations,
            AnalysisResult analysis) {
        var next = analysis.nextStrategy();
        if (state.turnCount() >= state.maximumTurnCount()
                && !"CLOSE_ROLEPLAY".equals(next.type())) {
            throw new IllegalArgumentException("최대 턴에서는 역할극을 마무리해야 합니다.");
        }
        if (!GOAL_QUESTION_STRATEGIES.contains(next.type())) return;
        var target =
                observations.currentGoalExposures().stream()
                        .filter(e -> e.microGoalId().equals(next.targetMicroGoalId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("지원 조정 목표가 승인 범위에 없습니다."));
        if (target.exposureBeforeInput().answerRevealed()) {
            throw new IllegalArgumentException("이미 정답을 제공한 목표를 다시 유도할 수 없습니다.");
        }
        RoleplayCodes.requireOneOf(
                RoleplayCodes.SUPPORT_LEVELS, next.recommendedSupportLevel(), "supportLevel");
        // 지원 감소는 다음 목표에 반영하고 같은 목표의 질문을 낮춰 반복하지 않는다.
        if (next.targetMicroGoalId().equals(state.currentMicroGoalId())
                && next.recommendedSupportLevel().charAt(1)
                        < state.currentSupportLevel().charAt(1)) {
            throw new IllegalArgumentException("같은 목표의 지원을 낮춰 다시 유도할 수 없습니다.");
        }
    }
}
