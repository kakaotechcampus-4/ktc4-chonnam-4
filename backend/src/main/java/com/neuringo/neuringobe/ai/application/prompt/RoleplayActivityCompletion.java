package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.ActivityDecision;

/** 활동 달성 제안은 독립 검수 전에 마무리 전략으로 연결한다. 달성 여부를 새로 판정하지 않는다. */
final class RoleplayActivityCompletion {
    private RoleplayActivityCompletion() {}

    static AnalysisResult apply(RoleplayTurnInput.State state, AnalysisResult analysis) {
        if (analysis.goalObservation() == null
                || analysis.goalObservation().activityGoal().decision()
                        != ActivityDecision.ACHIEVED) return analysis;
        if ("NOT_ASSESSABLE".equals(analysis.learningState()))
            throw new IllegalArgumentException("판정 불가 입력으로 활동 달성을 확정할 수 없습니다.");
        return new AnalysisResult(
                analysis.turnId(),
                analysis.responseAct(),
                analysis.relevance(),
                analysis.learningState(),
                analysis.primaryGap(),
                new AnalysisResult.NextStrategy(
                        "CLOSE_ROLEPLAY",
                        state.currentMicroGoalId(),
                        "승인된 활동 목표의 달성 근거를 검수한 뒤 역할극을 마무리한다.",
                        state.currentSupportLevel()),
                analysis.analysisConfidence(),
                analysis.goalObservation());
    }
}
