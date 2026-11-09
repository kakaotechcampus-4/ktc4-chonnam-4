package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.structured.IndeterminateLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AnalysisReviewTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final RoleplayTurnInput input = RoleplayFixtures.input("친구가 울고 있어");
    private final GoalObservationInput observations =
            com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                    .inputForScenario(input.scenario());
    private final GoalObservationOutput.Reference ref =
            new GoalObservationOutput.Reference(
                    RoleplayFixtures.TURN_ID,
                    RoleplayFixtures.MG_EMOTION,
                    "test-evidence:" + RoleplayFixtures.MG_EMOTION + ":0");

    @ParameterizedTest
    @EnumSource(GoalObservationOutput.ActivityDecision.class)
    void approvingAnAssessmentDoesNotChangeItsAchievementDecision(
            GoalObservationOutput.ActivityDecision decision) {
        var analysis = analysis(decision);
        var body = body(analysis);
        var result = parse(body, analysis);
        assertThat(result.goalObservationReview().activityGoalSupported()).isTrue();
        assertThat(analysis.activityGoal().decision()).isEqualTo(decision);
        assertThat(json.writeValueAsString(result))
                .doesNotContain("explanation", "evidence_references", "울고 있어");
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity", "logic", "strategy"})
    void derivesAnalysisRejectionAndCodesWithoutModelSuppliedSummary(String rejected) {
        var analysis = analysis(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
        var body = body(analysis);
        body.put("decision", "REANALYZE");
        if (rejected.equals("activity")) {
            activity(body)
                    .put("verdict", "UNSUPPORTED")
                    .put("reason_code", "CONTINUATION_NOT_GROUNDED");
        } else {
            ((ObjectNode)
                            body.path(
                                    rejected.equals("strategy")
                                            ? "interpretation_strategy_review"
                                            : "current_goal_review"))
                    .put("verdict", "UNSUPPORTED")
                    .put(
                            "reason_code",
                            rejected.equals("strategy")
                                    ? "STATE_STRATEGY_MISMATCH"
                                    : "LEARNING_STATE_MISMATCH");
        }
        var result = parse(body, analysis);
        assertThat(result.failureCodes()).contains("UNSUPPORTED_CAUSE_ANALYSIS");
        assertThat(result.failureCodes().contains("STATE_STRATEGY_MISMATCH"))
                .isEqualTo(rejected.equals("strategy"));
        assertThat(result.canDeliver()).isFalse();
        assertThat(result.goalObservationReview().activityGoalSupported())
                .isEqualTo(!rejected.equals("activity"));
        body.put("decision", "PASS");
        assertThatThrownBy(() -> parse(body, analysis))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "decision",
                "reason",
                "missing",
                "empty-explanation",
                "long-explanation",
                "duplicate",
                "foreign",
                "unobserved",
                "missing-positive"
            })
    void rejectsInvalidActivityReviews(String change) {
        var analysis = analysis(GoalObservationOutput.ActivityDecision.ACHIEVED);
        var body = body(analysis);
        var review = activity(body);
        switch (change) {
            case "decision" -> review.put("reviewed_decision", "IN_PROGRESS");
            case "reason" ->
                    review.put("verdict", "UNSUPPORTED")
                            .put("reason_code", "CONTINUATION_NOT_GROUNDED");
            case "missing" -> body.putNull("activity_review");
            case "empty-explanation" -> review.put("explanation", " ");
            case "long-explanation" -> review.put("explanation", "가".repeat(201));
            case "duplicate" ->
                    review.set("evidence_references", json.valueToTree(List.of(ref, ref)));
            case "foreign" ->
                    review.set(
                            "evidence_references",
                            json.valueToTree(
                                    List.of(
                                            new GoalObservationOutput.Reference(
                                                    UUID.randomUUID(),
                                                    ref.microGoalId(),
                                                    ref.criterionReference()))));
            case "unobserved" ->
                    review.set(
                            "evidence_references",
                            json.valueToTree(
                                    List.of(
                                            new GoalObservationOutput.Reference(
                                                    ref.turnId(),
                                                    RoleplayFixtures.MG_EVENT,
                                                    "test-evidence:"
                                                            + RoleplayFixtures.MG_EVENT
                                                            + ":0"))));
            case "missing-positive" -> review.putArray("evidence_references");
            default -> throw new AssertionError();
        }
        assertThatThrownBy(() -> parse(body, analysis))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity", "logic"})
    void preservesIndeterminateReviewsAsTechnicalFailures(String field) {
        var analysis = analysis(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
        var body = body(analysis);
        (field.equals("activity") ? activity(body) : logic(body))
                .put("verdict", "INSUFFICIENT_CONTEXT")
                .put("reason_code", "INSUFFICIENT_CONTEXT");
        assertThatThrownBy(() -> parse(body, analysis))
                .isInstanceOf(IndeterminateLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "reason", "empty", "long"})
    void rejectsInvalidLogicReview(String mode) {
        var analysis = analysis(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
        var body = body(analysis);
        switch (mode) {
            case "missing" -> body.putNull("current_goal_review");
            case "reason" -> logic(body).put("reason_code", "STATE_STRATEGY_MISMATCH");
            case "empty" -> logic(body).put("explanation", " ");
            case "long" -> logic(body).put("explanation", "가".repeat(201));
            default -> throw new AssertionError();
        }
        assertThatThrownBy(() -> parse(body, analysis))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRM_INPUT", "SAFETY_ESCALATION"})
    void priorityDecisionsCanSkipLearningReviewsWithoutApprovingThem(String decision) {
        var analysis = analysis(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
        var body = body(analysis);
        body.put("decision", decision);
        body.putNull("current_goal_review");
        body.putNull("activity_review");
        body.putNull("observation_reviews");
        body.putArray("failure_codes")
                .add(
                        decision.equals("CONFIRM_INPUT")
                                ? "INPUT_MEANING_UNCLEAR"
                                : "IMMEDIATE_RISK_IN_INPUT");
        var result = parse(body, analysis);
        assertThat(result.goalObservationReview().supportsAnalysis()).isFalse();
        assertThat(result.canDeliver()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "analysis_supported",
                "UNSUPPORTED_CAUSE_ANALYSIS",
                "STATE_STRATEGY_MISMATCH"
            })
    void rejectsModelSuppliedServerOwnedSummaryOrCodes(String field) {
        var analysis = analysis(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
        var body = body(analysis);
        if (field.equals("analysis_supported")) ((ObjectNode) body.path("checks")).put(field, true);
        else body.putArray("failure_codes").add(field);
        assertThatThrownBy(() -> parse(body, analysis))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void approvedAnalysisCannotRequestReanalysis() {
        var analysis = analysis(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
        var body = body(analysis);
        body.put("decision", "REANALYZE");
        assertThatThrownBy(() -> parse(body, analysis))
                .isInstanceOfSatisfying(
                        InvalidLlmOutputException.class,
                        failure ->
                                assertThat(EvaluationRetry.from(failure))
                                        .isEqualTo(EvaluationRetry.REVIEW_ANALYSIS_CONFLICT));
    }

    private GoalObservationOutput.Analysis analysis(
            GoalObservationOutput.ActivityDecision decision) {
        return new GoalObservationOutput.Analysis(
                "analysis-result/v2",
                RoleplayFixtures.ANALYSIS_ID,
                List.of(
                        new GoalObservationOutput.Observation(
                                ref, GoalObservationOutput.Status.EXPLICIT)),
                new GoalObservationOutput.Assessment(decision, List.of(ref)));
    }

    private ObjectNode body(GoalObservationOutput.Analysis analysis) {
        var result =
                (ObjectNode)
                        json.readTree(RoleplayFixtures.evaluationOutputJson("PASS", "[]", "null"));
        var context = ScopedTestContexts.context(input, analysis, observations);
        result =
                (ObjectNode)
                        json.readTree(
                                EvaluationWireFixtures.forRequest(
                                        result.toString(), context.input()));
        return result;
    }

    private ObjectNode activity(ObjectNode body) {
        return (ObjectNode) body.path("activity_review");
    }

    private ObjectNode logic(ObjectNode body) {
        return (ObjectNode) body.path("current_goal_review");
    }

    private com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult parse(
            ObjectNode body, GoalObservationOutput.Analysis analysis) {
        var context = ScopedTestContexts.context(input, analysis, observations);
        return RoleplayOutputParsers.nativeEvaluation(
                        json,
                        RoleplayFixtures.CANDIDATE_ID,
                        RoleplayFixtures.ANALYSIS_ID,
                        context,
                        new EvaluationOutputSchema(context, RoleplayFixtures.CANDIDATE_ID))
                .parse(body.toString());
    }
}
