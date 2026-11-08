package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.structured.IndeterminateLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class ObservationReviewValidatorTest {
    private final JsonMapper json =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void recordedEvidenceReviewsBindToCurrentGoalsAndDeriveOnlyObservationApproval(int index)
            throws Exception {
        var sample = sample(index);
        var input = input(sample);
        var analysis = analysis(sample);
        var observations = observations(sample);
        var reviews = reviews(sample);
        assertThat(new ObservationReviewValidator(input, analysis, observations).validate(reviews))
                .isEqualTo(index == 0);
        var body = json.readTree(RoleplayFixtures.evaluationOutputJson("PASS", "[]", "null"));
        var object = (tools.jackson.databind.node.ObjectNode) body;
        object.put("candidate_id", RoleplayFixtures.CANDIDATE_ID.toString());
        var context =
                ScopedTestContexts.context(
                        input,
                        analysis,
                        observations,
                        sample.path("input")
                                .path("analysis_result")
                                .path("learning_state")
                                .asString());
        object =
                (tools.jackson.databind.node.ObjectNode)
                        json.readTree(
                                EvaluationWireFixtures.forRequest(
                                        object.toString(), context.input()));
        var mapped = object.putObject("observation_reviews");
        for (int i = 0; i < reviews.size(); i++) {
            var review = (tools.jackson.databind.node.ObjectNode) json.valueToTree(reviews.get(i));
            review.remove("micro_goal_id");
            review.remove("criterion_reference");
            mapped.set("o" + i, review);
        }
        if (index == 1) object.put("decision", "REANALYZE");
        var result =
                RoleplayOutputParsers.nativeEvaluation(
                                json,
                                RoleplayFixtures.CANDIDATE_ID,
                                analysis.analysisId(),
                                context,
                                new EvaluationOutputSchema(context, RoleplayFixtures.CANDIDATE_ID))
                        .parse(object.toString());
        assertThat(result.goalObservationReview().observationsSupported()).isEqualTo(index == 0);
        assertThat(result.goalObservationReview().schemaVersion())
                .isEqualTo("evaluation-result/v2");
        assertThat(result.canDeliver()).isEqualTo(index == 0);
        assertThat(json.writeValueAsString(result))
                .doesNotContain(
                        "evidence_quote", "explanation", input.learnerTurn().canonicalUtterance());
    }

    @ParameterizedTest
    @EnumSource(GoalObservationOutput.Status.class)
    void approvalOfAnObservationClassificationIsDifferentFromAChildsAchievement(
            GoalObservationOutput.Status status) {
        var input = RoleplayFixtures.input("친구가 울고 있어");
        var ref =
                new GoalObservationOutput.Reference(
                        RoleplayFixtures.TURN_ID,
                        RoleplayFixtures.MG_EMOTION,
                        "test-evidence:" + RoleplayFixtures.MG_EMOTION + ":0");
        var analysis =
                new GoalObservationOutput.Analysis(
                        "analysis-result/v2",
                        RoleplayFixtures.ANALYSIS_ID,
                        List.of(new GoalObservationOutput.Observation(ref, status)),
                        new GoalObservationOutput.Assessment(
                                GoalObservationOutput.ActivityDecision.IN_PROGRESS, List.of()));
        var review =
                new ObservationReview(
                        ref.microGoalId(),
                        ref.criterionReference(),
                        status == GoalObservationOutput.Status.NOT_EXPRESSED ? null : "울고 있어",
                        ObservationReview.Verdict.SUPPORTED,
                        status == GoalObservationOutput.Status.NOT_EXPRESSED
                                ? "ABSENCE_CONFIRMED"
                                : "MATCHED_EXPRESSION",
                        "분류의 근거를 검수했다.");
        assertThat(
                        new ObservationReviewValidator(
                                        input,
                                        analysis,
                                        com.neuringo.neuringobe.roleplay.application
                                                .RoleplayObservationTestPlans.inputForScenario(
                                                input.scenario()))
                                .validate(List.of(review)))
                .isTrue();
        assertThat(analysis.activityGoal().decision())
                .isEqualTo(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "duplicate",
                "wrong-goal",
                "quote",
                "blank-explanation",
                "long-explanation",
                "reason"
            })
    void rejectsInvalidBindingsAndEvidence(String failure) throws Exception {
        var sample = sample(0);
        var valid = reviews(sample).getFirst();
        List<ObservationReview> values = new ArrayList<>(List.of(valid));
        switch (failure) {
            case "missing" -> values.clear();
            case "duplicate" -> values.add(valid);
            case "wrong-goal" ->
                    values.set(
                            0,
                            new ObservationReview(
                                    UUID.randomUUID(),
                                    valid.criterionReference(),
                                    valid.evidenceQuote(),
                                    valid.verdict(),
                                    valid.reasonCode(),
                                    valid.explanation()));
            case "quote" ->
                    values.set(0, copy(valid, "없는 원문", valid.reasonCode(), valid.explanation()));
            case "blank-explanation" ->
                    values.set(0, copy(valid, valid.evidenceQuote(), valid.reasonCode(), " "));
            case "long-explanation" ->
                    values.set(
                            0,
                            copy(
                                    valid,
                                    valid.evidenceQuote(),
                                    valid.reasonCode(),
                                    "가".repeat(201)));
            case "reason" ->
                    values.set(
                            0,
                            copy(
                                    valid,
                                    valid.evidenceQuote(),
                                    "ABSENCE_CONFIRMED",
                                    valid.explanation()));
            default -> throw new AssertionError();
        }
        assertThatThrownBy(
                        () ->
                                new ObservationReviewValidator(
                                                input(sample),
                                                analysis(sample),
                                                observations(sample))
                                        .validate(values))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void indeterminateOutputIsDistinctFromMalformedOutputAndDoesNotHideARejection()
            throws Exception {
        var sample = sample(1);
        var values = new ArrayList<>(reviews(sample));
        var first = values.getFirst();
        values.set(
                0,
                new ObservationReview(
                        first.microGoalId(),
                        first.criterionReference(),
                        null,
                        ObservationReview.Verdict.INSUFFICIENT_CONTEXT,
                        "INSUFFICIENT_CONTEXT",
                        "문맥이 부족하다."));
        var validator =
                new ObservationReviewValidator(
                        input(sample), analysis(sample), observations(sample));
        assertThat(validator.validate(values)).isFalse();
        values.remove(1);
        var only = sample(0);
        assertThatThrownBy(
                        () ->
                                new ObservationReviewValidator(
                                                input(only), analysis(only), observations(only))
                                        .validate(values))
                .isInstanceOf(IndeterminateLlmOutputException.class);
    }

    @Test
    void emptyCurrentObservationsDoNotCreateAnAchievement() {
        var input = RoleplayFixtures.input("모르겠어요");
        var analysis =
                new GoalObservationOutput.Analysis(
                        "analysis-result/v2",
                        RoleplayFixtures.ANALYSIS_ID,
                        List.of(),
                        new GoalObservationOutput.Assessment(
                                GoalObservationOutput.ActivityDecision.IN_PROGRESS, List.of()));
        assertThat(
                        new ObservationReviewValidator(
                                        input,
                                        analysis,
                                        com.neuringo.neuringobe.roleplay.application
                                                .RoleplayObservationTestPlans.inputForScenario(
                                                input.scenario()))
                                .validate(List.of()))
                .isTrue();
        assertThat(analysis.activityGoal().decision())
                .isEqualTo(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
    }

    @Test
    void indeterminateMappingPreservesUsageWithoutTreatingItAsMalformedOrProviderFailure() {
        var request =
                new com.neuringo.neuringobe.ai.application.model.LlmRequest(
                        RoleplayFixtures.trace(
                                RoleplayFixtures.ANALYSIS_ID, RoleplayFixtures.CANDIDATE_ID),
                        RoleplayFixtures.attempts(),
                        com.neuringo.neuringobe.ai.application.model.AiOperation
                                .RESPONSE_EVALUATION,
                        1,
                        "검수",
                        "{}",
                        "test",
                        "test",
                        null);
        var metadata =
                new com.neuringo.neuringobe.ai.application.model.AiCallMetadata(
                        request.traceContext().requestId(),
                        request.operation(),
                        "fake",
                        "fake",
                        "test",
                        "test",
                        null,
                        1,
                        1,
                        11,
                        7,
                        "stop");
        var executor =
                new com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor(
                        ignored ->
                                new com.neuringo.neuringobe.ai.application.model.AiCallResult
                                        .Success<>(
                                        new com.neuringo.neuringobe.ai.application.model
                                                .LlmCompletion(
                                                "합성 응답 원문", "fake", "fake", "stop", 11, 7),
                                        metadata));
        var result =
                (com.neuringo.neuringobe.ai.application.model.AiCallResult.Failure<?>)
                        executor.execute(
                                request,
                                ignored -> {
                                    throw new IndeterminateLlmOutputException();
                                });
        assertThat(result.failure().type())
                .isEqualTo(
                        com.neuringo.neuringobe.ai.application.model.AiFailureType
                                .INDETERMINATE_OUTPUT);
        assertThat(result.failure().retryable()).isTrue();
        assertThat(result.metadata()).isSameAs(metadata);
        assertThat(result.failure().toString()).doesNotContain("합성 응답 원문");
    }

    private ObservationReview copy(
            ObservationReview v, String quote, String reason, String explanation) {
        return new ObservationReview(
                v.microGoalId(), v.criterionReference(), quote, v.verdict(), reason, explanation);
    }

    private JsonNode sample(int index) throws Exception {
        try (var stream =
                getClass()
                        .getResourceAsStream(
                                "/ai/mock/roleplay-observation-evidence-replay.json")) {
            return json.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .get(index);
        }
    }

    private RoleplayTurnInput input(JsonNode sample) {
        var i = sample.path("input");
        return new RoleplayTurnInput(
                json.treeToValue(i.path("scenario"), RoleplayTurnInput.Scenario.class),
                json.treeToValue(
                        ((tools.jackson.databind.node.ObjectNode) i.path("state"))
                                .deepCopy()
                                .put("current_micro_goal_mascot_hints", 0)
                                .put("previous_turn_had_mascot_hint", false),
                        RoleplayTurnInput.State.class),
                json.treeToValue(i.path("learner_turn"), RoleplayTurnInput.LearnerTurn.class),
                List.of());
    }

    private GoalObservationOutput.Analysis analysis(JsonNode sample) {
        return json.treeToValue(
                sample.path("input").path("analysis_result").path("goal_observation"),
                GoalObservationOutput.Analysis.class);
    }

    private GoalObservationInput observations(JsonNode sample) {
        return json.treeToValue(
                sample.path("input").path("goal_observation_context"), GoalObservationInput.class);
    }

    private List<ObservationReview> reviews(JsonNode sample) {
        var result = new ArrayList<ObservationReview>();
        for (var raw : sample.path("output").path("reviews")) {
            String ref = raw.path("criterion_reference").asString();
            var goal =
                    analysis(sample).observations().stream()
                            .filter(o -> o.reference().criterionReference().equals(ref))
                            .findFirst()
                            .orElseThrow()
                            .reference()
                            .microGoalId();
            result.add(
                    new ObservationReview(
                            goal,
                            ref,
                            raw.path("evidence_quote").isNull()
                                    ? null
                                    : raw.path("evidence_quote").asString(),
                            ObservationReview.Verdict.valueOf(raw.path("verdict").asString()),
                            raw.path("reason_code").asString(),
                            raw.path("explanation").asString()));
        }
        return result;
    }
}
