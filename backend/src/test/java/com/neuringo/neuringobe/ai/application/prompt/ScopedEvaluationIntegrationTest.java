package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ScopedEvaluationIntegrationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void emptyObservationsRemainEmptyAndDoNotCompleteTheActivity() {
        var context = context(false, 0);
        var body = body(context);
        assertThat(body.path("observation_reviews").size()).isZero();
        var result = parse(context, body, RoleplayFixtures.CANDIDATE_ID);
        assertThat(result.goalObservationReview().observationsSupported()).isTrue();
        assertThat(context.input().path("activity_assessment").path("proposed_decision").asString())
                .isEqualTo("IN_PROGRESS");
        assertThat(result.canDeliver()).isTrue();
        assertThat(json.writeValueAsString(result))
                .doesNotContain(
                        "observation_reviews",
                        "explanation",
                        "evidence_references",
                        "current_goal_review");
    }

    @Test
    void bindsMultipleCriteriaOfTheSameGoalWithoutReusingAnotherRequestsSchema() {
        var one = context(false, 1);
        var two = context(true, 2);
        var first = body(one);
        var second = body(two);
        assertThat(first.path("observation_reviews").propertyNames()).containsExactly("o0");
        assertThat(second.path("observation_reviews").propertyNames()).containsExactly("o0", "o1");
        assertThat(parse(two, second, RoleplayFixtures.CANDIDATE_ID).canDeliver()).isTrue();
        assertThatThrownBy(() -> parse(one, second, RoleplayFixtures.CANDIDATE_ID))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(() -> parse(two, first, RoleplayFixtures.CANDIDATE_ID))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "strategy"})
    void rejectionInEitherAdditionalAnalysisGateCannotSlipThroughTheInternalGoalReview(
            String group) {
        var context = context(false, 1);
        var body = body(context);
        var review =
                (ObjectNode)
                        body.path(
                                group.equals("current")
                                        ? "current_goal_review"
                                        : "interpretation_strategy_review");
        review.put("verdict", "UNSUPPORTED")
                .put(
                        "reason_code",
                        group.equals("current")
                                ? "LEARNING_STATE_MISMATCH"
                                : "STATE_STRATEGY_MISMATCH");
        assertThatThrownBy(() -> parse(context, body, RoleplayFixtures.CANDIDATE_ID))
                .isInstanceOf(InvalidLlmOutputException.class);
        body.put("decision", "REANALYZE");
        var result = parse(context, body, RoleplayFixtures.CANDIDATE_ID);
        assertThat(result.goalObservationReview().supportsAnalysis()).isTrue();
        assertThat(result.failureCodes()).contains("UNSUPPORTED_CAUSE_ANALYSIS");
        assertThat(result.canDeliver()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRM_INPUT", "SAFETY_ESCALATION"})
    void priorityCanSkipAllFourLearningReviewsButStillRequiresValidMetadata(String decision) {
        var context = context(false, 1);
        var body = body(context);
        body.put("decision", decision);
        for (String key :
                List.of(
                        "observation_reviews",
                        "current_goal_review",
                        "activity_review",
                        "interpretation_strategy_review")) body.putNull(key);
        body.putArray("failure_codes")
                .add(
                        decision.equals("CONFIRM_INPUT")
                                ? "INPUT_MEANING_UNCLEAR"
                                : "IMMEDIATE_RISK_IN_INPUT");
        var result = parse(context, body, RoleplayFixtures.CANDIDATE_ID);
        assertThat(result.goalObservationReview().supportsAnalysis()).isFalse();
        assertThat(result.canDeliver()).isFalse();
        body.put("analysis_id", UUID.randomUUID().toString());
        assertThatThrownBy(() -> parse(context, body, RoleplayFixtures.CANDIDATE_ID))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void candidateBindingCannotBeReusedForAnotherCandidate() {
        var context = context(false, 1);
        var body = body(context);
        assertThatThrownBy(() -> parse(context, body, UUID.randomUUID()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B"})
    void previousScopedDiagnosticVersionKeepsItsRecordedSchema(String sample) throws Exception {
        try (var original =
                        getClass()
                                .getResourceAsStream(
                                        "/eval/analysis-review-observation-pair-p1-v1.json");
                var snapshot =
                        getClass()
                                .getResourceAsStream(
                                        "/eval/scoped-analysis-v1-" + sample + ".schema.json")) {
            var source = json.readTree(new String(original.readAllBytes(), StandardCharsets.UTF_8));
            if (sample.equals("B")) {
                var input = source.path("input");
                var target =
                        input.path("analysis_result")
                                .path("next_strategy")
                                .path("target_micro_goal_id");
                var added =
                        ((tools.jackson.databind.node.ArrayNode)
                                        input.path("analysis_result")
                                                .path("goal_observation")
                                                .path("observations"))
                                .addObject()
                                .put("status", "EXPLICIT");
                var ref = added.putObject("reference");
                ref.set("turn_id", input.path("learner_turn").path("turn_id"));
                ref.set("micro_goal_id", target);
                for (var criterion :
                        input.path("goal_observation_context").path("approved_criteria"))
                    if (criterion.path("micro_goal_id").equals(target))
                        ref.set("criterion_reference", criterion.path("reference"));
            }
            assertThat(json.readTree(new ScopedAnalysisValidation(source.path("input")).schema()))
                    .isEqualTo(
                            json.readTree(
                                    new String(snapshot.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    private ScopedAnalysisContext context(boolean multipleCriteria, int count) {
        var original = RoleplayFixtures.input("친구가 울고 있어");
        var input = original;
        if (multipleCriteria) {
            var goals =
                    original.scenario().microGoals().stream()
                            .map(
                                    goal ->
                                            goal.id().equals(RoleplayFixtures.MG_EMOTION)
                                                    ? new RoleplayTurnInput.MicroGoal(
                                                            goal.id(),
                                                            goal.description(),
                                                            List.of(
                                                                    goal.requiredEvidence()
                                                                            .getFirst(),
                                                                    "감정 단서의 두 번째 기준"),
                                                            goal.status())
                                                    : goal)
                            .toList();
            var s = original.scenario();
            input =
                    new RoleplayTurnInput(
                            new RoleplayTurnInput.Scenario(
                                    s.learningGoal(),
                                    s.scenarioLevel(),
                                    s.scenarioFacts(),
                                    s.prohibitedInferences(),
                                    goals,
                                    s.aiCharacter()),
                            original.state(),
                            original.learnerTurn(),
                            original.recentDialogue());
        }
        var observations =
                com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                        .inputForScenario(input.scenario());
        var rows = new ArrayList<GoalObservationOutput.Observation>();
        for (int index = 0; index < count; index++)
            rows.add(
                    new GoalObservationOutput.Observation(
                            new GoalObservationOutput.Reference(
                                    RoleplayFixtures.TURN_ID,
                                    RoleplayFixtures.MG_EMOTION,
                                    "test-evidence:" + RoleplayFixtures.MG_EMOTION + ":" + index),
                            GoalObservationOutput.Status.NOT_EXPRESSED));
        return ScopedTestContexts.context(
                input,
                new GoalObservationOutput.Analysis(
                        "analysis-result/v2",
                        RoleplayFixtures.ANALYSIS_ID,
                        rows,
                        new GoalObservationOutput.Assessment(
                                GoalObservationOutput.ActivityDecision.IN_PROGRESS, List.of())),
                observations);
    }

    private ObjectNode body(ScopedAnalysisContext context) {
        return (ObjectNode)
                json.readTree(
                        EvaluationWireFixtures.forRequest(
                                RoleplayFixtures.evaluationOutputJson("PASS", "[]", "null"),
                                context.input()));
    }

    private com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult parse(
            ScopedAnalysisContext context, ObjectNode body, UUID candidate) {
        return RoleplayOutputParsers.nativeEvaluation(
                        json,
                        candidate,
                        RoleplayFixtures.ANALYSIS_ID,
                        context,
                        new EvaluationOutputSchema(context, candidate))
                .parse(body.toString());
    }
}
