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
        requireValidModelAnalysis(state, observations, analysis);
        RoleplaySupportApplication.requireValid(state, observations, analysis);
    }

    public static void requireValidModelAnalysis(
            RoleplayTurnInput.State state,
            GoalObservationInput observations,
            AnalysisResult analysis) {
        var next = analysis.nextStrategy();
        RoleplayCodes.requireOneOf(
                RoleplayCodes.SUPPORT_LEVELS, next.recommendedSupportLevel(), "supportLevel");
        if (state.learningTurnCount() != null
                && state.learningTurnCount() >= state.maximumTurnCount()
                && !analysis.learningState().equals("NOT_ASSESSABLE")
                && (!next.type().equals("CLOSE_ROLEPLAY")
                        || !next.targetMicroGoalId().equals(state.currentMicroGoalId())
                        || !next.recommendedSupportLevel().equals(state.currentSupportLevel()))) {
            throw new IllegalArgumentException("전체 학습 턴 상한에서는 현재 목표·지원 수준으로 마무리해야 합니다.");
        }
        if (analysis.learningState().equals("GOAL_ACHIEVED")
                && !next.targetMicroGoalId().equals(state.currentMicroGoalId())
                && !next.type().equals("CLOSE_ROLEPLAY")
                && !GOAL_QUESTION_STRATEGIES.contains(next.type()))
            throw new IllegalArgumentException("목표 도달 뒤 새 목표의 시작은 학습 질문 전략이어야 합니다.");
        if (analysis.learningState().equals("NOT_ASSESSABLE")
                && (!next.targetMicroGoalId().equals(state.currentMicroGoalId())
                        || !next.recommendedSupportLevel().equals(state.currentSupportLevel()))) {
            throw new IllegalArgumentException("판정 불가 입력으로 질문 목표·지원 수준을 바꿀 수 없습니다.");
        }
        if (!next.type().equals("CLOSE_ROLEPLAY")
                && next.targetMicroGoalId().equals(state.currentMicroGoalId())
                && next.recommendedSupportLevel().compareTo(state.currentSupportLevel()) < 0) {
            throw new IllegalArgumentException("같은 목표의 지원을 낮춰 다시 유도할 수 없습니다.");
        }
        if (!next.type().equals("CLOSE_ROLEPLAY")
                && next.targetMicroGoalId().equals(state.currentMicroGoalId())
                && next.recommendedSupportLevel().charAt(1)
                        > state.currentSupportLevel().charAt(1) + 1) {
            throw new IllegalArgumentException("같은 목표의 지원은 한 번에 한 단계만 높일 수 있습니다.");
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
    }
}
