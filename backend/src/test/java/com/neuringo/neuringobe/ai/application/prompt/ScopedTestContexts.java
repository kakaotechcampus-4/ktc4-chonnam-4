package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** 파서 단위 검사에 필요한 동일한 원본과 타입 입력을 구성한다. */
final class ScopedTestContexts {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    private ScopedTestContexts() {}

    static ScopedAnalysisContext context(
            RoleplayTurnInput input,
            GoalObservationOutput.Analysis goal,
            GoalObservationInput observations) {
        return context(input, goal, observations, "PARTIAL_UNDERSTANDING");
    }

    static ScopedAnalysisContext context(
            RoleplayTurnInput input,
            GoalObservationOutput.Analysis goal,
            GoalObservationInput observations,
            String learningState) {
        var analysis =
                new AnalysisResult(
                        input.learnerTurn().turnId(),
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        learningState,
                        new AnalysisResult.PrimaryGap("NONE", "합성 분석"),
                        new AnalysisResult.NextStrategy(
                                "PROBE_EMOTION",
                                input.state().currentMicroGoalId(),
                                "합성 전략",
                                input.state().currentSupportLevel()),
                        .9,
                        goal);
        var source = JSON.createObjectNode();
        source.set("scenario", JSON.valueToTree(input.scenario()));
        source.set("state", JSON.valueToTree(input.state()));
        source.set("learner_turn", JSON.valueToTree(input.learnerTurn()));
        source.set("recent_dialogue", JSON.valueToTree(input.recentDialogue()));
        source.set("analysis_result", JSON.valueToTree(analysis));
        source.set("goal_observation_context", JSON.valueToTree(observations));
        source.put("analysis_id", goal.analysisId().toString());
        return new ScopedAnalysisContext(source, input, analysis, observations);
    }
}
