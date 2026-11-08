package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class EvaluationOutputSchemaTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({
        "REANALYZE,CAUSE_ANALYSIS",
        "REGENERATE,RESPONSE_GENERATION",
        "SAFETY_REGENERATE,RESPONSE_GENERATION",
        "CONFIRM_INPUT,INPUT_CONFIRMATION",
        "SAFETY_ESCALATION,SAFETY_ESCALATION"
    })
    void serverDerivesRoutingAndCriticalCountWithoutModelSuppliedFields(
            String decision, String target) {
        String revision = "{\"keep\":[],\"change\":[],\"avoid\":[],\"required\":[]}";
        var body =
                json.readValue(
                        RoleplayFixtures.evaluationOutputJson(
                                decision, "[\"UNSAFE_EXPRESSION\"]", revision),
                        Map.class);
        if (decision.equals("REANALYZE"))
            ((Map<String, Object>) body.get("current_goal_review"))
                    .putAll(EvaluationWireFixtures.logicReview(false));
        var result = parse(json.writeValueAsString(body));
        assertThat(result.retryTarget()).isEqualTo(RetryTarget.valueOf(target));
        assertThat(result.safeToSend()).isFalse();
        assertThat(result.criticalFailureCount()).isEqualTo(1);
    }

    @Test
    void passIsDeliveredOnlyAfterChecksAndGoalReviewAreApproved() {
        var result = parse(RoleplayFixtures.evaluationOutputJson("PASS", "[]", "null"));
        assertThat(result.canDeliver()).isTrue();
        assertThat(result.retryTarget()).isNull();
        assertThat(result.criticalFailureCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "format_valid",
                "safety_valid",
                "privacy_valid",
                "scenario_consistent",
                "analysis_response_consistent",
                "goal_aligned",
                "educationally_correct",
                "difficulty_appropriate",
                "conversation_coherent",
                "answerable",
                "non_repetitive"
            })
    void passCannotOverrideAnyRejectedCheck(String field) {
        var body = body();
        ((Map<String, Object>) body.get("checks")).put(field, false);
        assertRejected(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry_target", "safe_to_send", "critical_failure_count", "unexpected"})
    void modelCannotSupplyServerOwnedOrUnknownFields(String field) {
        var body = body();
        body.put(field, null);
        assertRejected(body);
    }

    @Test
    void missingAndCoercedChecksAreRejected() {
        for (Object value : new Object[] {"true", 1, null}) {
            var body = body();
            ((Map<String, Object>) body.get("checks")).put("safety_valid", value);
            assertRejected(body);
        }
        var body = body();
        ((Map<String, Object>) body.get("checks")).remove("safety_valid");
        assertRejected(body);
    }

    @Test
    void passCannotOverrideUnsupportedGoalReview() {
        var body = body();
        body.put(
                "activity_review",
                EvaluationWireFixtures.activityReview(json.createObjectNode(), false));
        assertRejected(body);
    }

    @Test
    void nativeSchemaAndServerUseExactlyTheSameFailureCodes() {
        var schema = EvaluationSchemaParts.properties();
        var values = schema.path("failure_codes").path("items").path("enum");
        var actual = new java.util.HashSet<String>();
        values.forEach(v -> actual.add(v.asString()));
        var expected = new java.util.HashSet<>(RoleplayCodes.FAILURE_CODES);
        expected.removeAll(AnalysisReview.SERVER_CODES);
        assertThat(actual).isEqualTo(expected);
        assertThatThrownBy(
                        () ->
                                parse(
                                        RoleplayFixtures.evaluationOutputJson(
                                                "REANALYZE", "[\"ANALYSIS_SUPPORTED\"]", "null")))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void reanalysisWithoutRetryTargetIsValidButRegenerationWithoutRevisionIsRejected() {
        assertThat(
                        parse(
                                        RoleplayFixtures.evaluationOutputJson(
                                                "REANALYZE",
                                                "[\"UNSUPPORTED_CAUSE_ANALYSIS\"]",
                                                "null"))
                                .retryTarget())
                .isEqualTo(RetryTarget.CAUSE_ANALYSIS);
        assertThatThrownBy(
                        () ->
                                parse(
                                        RoleplayFixtures.evaluationOutputJson(
                                                "REGENERATE", "[\"TOO_LONG\"]", "null")))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void recordedV1EvaluationsCannotBypassTheV2Contract(int index) throws Exception {
        String content =
                RoleplayFixtures.recordedCharacterEvaluation(index)
                        .replace("${candidate_id}", RoleplayFixtures.CANDIDATE_ID.toString())
                        .replace("${analysis_id}", RoleplayFixtures.ANALYSIS_ID.toString());
        assertThatThrownBy(() -> parse(content))
                .isInstanceOfSatisfying(
                        InvalidLlmOutputException.class,
                        failure ->
                                assertThat(EvaluationRetry.from(failure))
                                        .isEqualTo(EvaluationRetry.INVALID_STRUCTURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"observations", "activity", "logic"})
    void rejectedReviewDerivesAnalysisFailureWithoutASecondModelBoolean(String field) {
        var body = body();
        body.put("decision", "REANALYZE");
        if (field.equals("observations")) {
            var row = (Map<String, Object>) ((Map<?, ?>) body.get("observation_reviews")).get("o0");
            row.put("verdict", "UNSUPPORTED");
            row.put("reason_code", "STATUS_MISMATCH");
            row.put("evidence_quote", "친구가 울고 있어");
        } else if (field.equals("activity")) {
            body.put(
                    "activity_review",
                    EvaluationWireFixtures.activityReview(json.createObjectNode(), false));
        } else
            ((Map<String, Object>) body.get("current_goal_review"))
                    .putAll(EvaluationWireFixtures.logicReview(false));
        var result = parse(json.writeValueAsString(body));
        assertThat(result.retryTarget()).isEqualTo(RetryTarget.CAUSE_ANALYSIS);
        assertThat(result.failureCodes()).contains("UNSUPPORTED_CAUSE_ANALYSIS");
        assertThat(result.canDeliver()).isFalse();
        body.put("decision", "PASS");
        assertRejected(body);
    }

    @ParameterizedTest
    @CsvSource({
        "GOAL_SKIPPED,goal_aligned",
        "ANALYSIS_RESPONSE_MISMATCH,analysis_response_consistent"
    })
    void failureCodeRequiresItsRelatedCheckToFail(String code, String check) {
        var body = body();
        body.put("decision", "REGENERATE");
        body.put(
                "revision_instruction",
                java.util.Map.of(
                        "keep",
                        java.util.List.of(),
                        "change",
                        java.util.List.of(),
                        "avoid",
                        java.util.List.of(),
                        "required",
                        java.util.List.of()));
        body.put("failure_codes", java.util.List.of(code));
        assertRetryReason(body, EvaluationRetry.FAILURE_CHECK_CONFLICT);
        ((Map<String, Object>) body.get("checks")).put(check, false);
        assertThat(parse(json.writeValueAsString(body)).retryTarget())
                .isEqualTo(RetryTarget.RESPONSE_GENERATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGENERATE", "SAFETY_REGENERATE"})
    void regenerationRequiresAnalysisApprovalInChecksAsWellAsGoalReview(String decision) {
        var body =
                json.readValue(
                        RoleplayFixtures.evaluationOutputJson(
                                decision,
                                "[\"UNSUPPORTED_CAUSE_ANALYSIS\"]",
                                "{\"keep\":[],\"change\":[],\"avoid\":[],\"required\":[]}"),
                        Map.class);
        assertRetryReason(body, EvaluationRetry.REVIEW_ANALYSIS_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRM_INPUT", "SAFETY_ESCALATION"})
    void safetyAndInputConfirmationKeepPriorityOverLearningConsistency(String decision) {
        var body = body();
        body.put("decision", decision);
        body.put("failure_codes", java.util.List.of("GOAL_SKIPPED"));
        body.put(
                "activity_review",
                EvaluationWireFixtures.activityReview(json.createObjectNode(), false));
        body.put("observation_reviews", null);
        assertThat(parse(json.writeValueAsString(body)).decision().name()).isEqualTo(decision);
        assertThat(parse(json.writeValueAsString(body)).canDeliver()).isFalse();
    }

    private void assertRetryReason(Map<String, Object> body, EvaluationRetry reason) {
        assertThatThrownBy(() -> parse(json.writeValueAsString(body)))
                .isInstanceOfSatisfying(
                        InvalidLlmOutputException.class,
                        failure -> assertThat(EvaluationRetry.from(failure)).isEqualTo(reason));
    }

    private Map<String, Object> body() {
        return json.readValue(
                RoleplayFixtures.evaluationOutputJson("PASS", "[]", "null"), Map.class);
    }

    private void assertRejected(Map<String, Object> body) {
        assertThatThrownBy(() -> parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    private com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult parse(
            String content) {
        var input = RoleplayFixtures.input("친구가 울고 있어");
        var reference =
                new GoalObservationOutput.Reference(
                        RoleplayFixtures.TURN_ID,
                        RoleplayFixtures.MG_EMOTION,
                        "test-evidence:" + RoleplayFixtures.MG_EMOTION + ":0");
        var analysis =
                new GoalObservationOutput.Analysis(
                        "analysis-result/v2",
                        RoleplayFixtures.ANALYSIS_ID,
                        java.util.List.of(
                                new GoalObservationOutput.Observation(
                                        reference, GoalObservationOutput.Status.NOT_EXPRESSED)),
                        new GoalObservationOutput.Assessment(
                                GoalObservationOutput.ActivityDecision.IN_PROGRESS,
                                java.util.List.of()));
        var context =
                ScopedTestContexts.context(
                        input,
                        analysis,
                        com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                                .inputForScenario(input.scenario()));
        return RoleplayOutputParsers.nativeEvaluation(
                        json,
                        RoleplayFixtures.CANDIDATE_ID,
                        RoleplayFixtures.ANALYSIS_ID,
                        context,
                        new EvaluationOutputSchema(context, RoleplayFixtures.CANDIDATE_ID))
                .parse(content);
    }
}
