package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.*;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RoleplayActivityCompletionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RoleplayPromptFactory prompts = new RoleplayPromptFactory(JSON, null, true);
    private final RoleplayTurnInput input = characterInput("넘어져서 많이 아프고 속상하겠다.");
    private final GoalObservationInput observations =
            RoleplayObservationTestPlans.inputForScenario(input.scenario());

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void oneInputCanSupplySeveralGoalsAndCloseWithoutVisitingThemInOrder(int positiveGoals) {
        var goals = List.of(MG_EMOTION, MG_LINK, MG_EVENT).subList(0, positiveGoals);
        var prepared = analysis(observations);
        var result = prepared.parser().parse(analysisBody(prepared.request(), goals, true));

        assertThat(result.goalObservation().observations())
                .filteredOn(o -> o.status() == GoalObservationOutput.Status.EXPLICIT)
                .extracting(o -> o.reference().microGoalId())
                .containsExactlyInAnyOrderElementsOf(goals);
        assertThat(result.nextStrategy().type()).isEqualTo("CLOSE_ROLEPLAY");
        assertThat(result.nextStrategy().targetMicroGoalId())
                .isEqualTo(input.state().currentMicroGoalId());
        assertThat(result.nextStrategy().recommendedSupportLevel())
                .isEqualTo(input.state().currentSupportLevel());
        assertThat(result.goalObservation().activityGoal().basis()).hasSize(positiveGoals);
        assertThat(result.learningState()).isEqualTo("PARTIAL_UNDERSTANDING");
    }

    @Test
    void confirmedEvidenceAndCurrentEvidenceRemainDistinctWhenActivityCloses() {
        var criterion =
                observations.approvedCriteria().stream()
                        .filter(c -> c.microGoalId().equals(MG_EVENT))
                        .findFirst()
                        .orElseThrow();
        var prior =
                new GoalObservationOutput.Observation(
                        new GoalObservationOutput.Reference(
                                UUID.randomUUID(), MG_EVENT, criterion.reference()),
                        GoalObservationOutput.Status.EXPLICIT);
        var history =
                RoleplayObservationTestPlans.withSyntheticObservationContexts(
                        observations.approvedCriteria(), List.of(prior));
        var prepared = analysis(history);
        var body =
                (ObjectNode)
                        JSON.readTree(
                                analysisBody(
                                        prepared.request(), List.of(MG_EMOTION, MG_LINK), true));
        body.path("activity_goal").withArray("basis").add(JSON.valueToTree(prior.reference()));
        var result = prepared.parser().parse(body.toString());

        assertThat(result.goalObservation().activityGoal().basis())
                .hasSize(3)
                .contains(prior.reference());
        assertThat(result.goalObservation().observations())
                .filteredOn(o -> o.reference().microGoalId().equals(MG_EVENT))
                .allMatch(o -> o.status() == GoalObservationOutput.Status.NOT_EXPRESSED);
        assertThat(result.nextStrategy().type()).isEqualTo("CLOSE_ROLEPLAY");
        var supplied =
                JSON.readTree(prepared.request().userPrompt()).path("goal_observation_context");
        assertThat(supplied.path("confirmed_observations").toString())
                .contains(prior.reference().turnId().toString());
    }

    @Test
    void positiveObservationsAloneDoNotAutomaticallyDeclareActivityAchieved() {
        var prepared = analysis(observations);
        var result =
                prepared.parser()
                        .parse(
                                analysisBody(
                                        prepared.request(),
                                        List.of(MG_EVENT, MG_EMOTION, MG_LINK),
                                        false));
        assertThat(result.goalObservation().activityGoal().decision())
                .isEqualTo(GoalObservationOutput.ActivityDecision.IN_PROGRESS);
        assertThat(result.nextStrategy().type()).isEqualTo("ADVANCE_GOAL");
    }

    @Test
    void unassessableInputCannotTurnIntoActivityCompletion() {
        var prepared = analysis(observations);
        var body =
                (ObjectNode)
                        JSON.readTree(analysisBody(prepared.request(), List.of(MG_EVENT), true));
        body.put("learning_state", "NOT_ASSESSABLE");
        assertThatThrownBy(() -> prepared.parser().parse(body.toString()))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void closingUsesThreeCallsAndStillRequiresIndependentReview() {
        var requests = new ArrayList<LlmRequest>();
        LlmProvider provider =
                request -> {
                    requests.add(request);
                    String raw =
                            switch (request.operation()) {
                                case CAUSE_ANALYSIS ->
                                        analysisBody(request, List.of(MG_EMOTION, MG_LINK), true);
                                case RESPONSE_GENERATION -> {
                                    var body = JSON.readTree(request.userPrompt());
                                    assertThat(body.toString())
                                            .contains("CLOSE_ROLEPLAY", "CLOSING");
                                    yield "{\"text\":\"고마워. 네가 내 마음을 알아줘서 힘이 나.\",\"mascot_hint\":null}";
                                }
                                case RESPONSE_EVALUATION -> {
                                    var body = JSON.readTree(request.userPrompt());
                                    assertThat(
                                                    body.path("activity_assessment")
                                                            .path("proposed_decision")
                                                            .asString())
                                            .isEqualTo("ACHIEVED");
                                    var evaluation =
                                            (ObjectNode)
                                                    JSON.readTree(
                                                            evaluationJson(
                                                                    "PASS", null, true, 0, "[]",
                                                                    "null"));
                                    evaluation.put(
                                            "candidate_id",
                                            request.traceContext().candidateId().toString());
                                    yield EvaluationWireFixtures.forRequest(
                                            evaluation.toString(), body);
                                }
                                default -> throw new AssertionError(request.operation());
                            };
                    return success(request, raw);
                };
        var steps =
                new StructuredRoleplayTurnSteps(
                        prompts,
                        new StructuredLlmExecutor(provider),
                        trace(null, null),
                        input,
                        observations);
        var result = new RetryingRoleplayTurnExecutor().execute(TURN_ID, steps);
        assertThat(result)
                .isInstanceOfSatisfying(
                        RoleplayTurnResult.Ready.class,
                        ready -> {
                            assertThat(ready.candidate().responseType()).isEqualTo("CLOSING");
                            assertThat(ready.evaluation().canDeliver()).isTrue();
                        });
        assertThat(requests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
    }

    @Test
    void aGroundlessActivityAchievementStillReturnsToAnalysis() {
        var prepared = analysis(observations);
        var analyzed =
                prepared.parser()
                        .parse(analysisBody(prepared.request(), List.of(MG_EMOTION), true));
        var generation =
                prompts.responseGeneration(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        input,
                        analyzed,
                        CANDIDATE_ID,
                        null);
        var candidate =
                generation
                        .parser()
                        .parse("{\"text\":\"네 말을 들으니 힘이 나. 고마워.\",\"mascot_hint\":null}");
        var review =
                prompts.responseEvaluation(
                        trace(ANALYSIS_ID, CANDIDATE_ID),
                        attempts(),
                        1,
                        input,
                        analyzed,
                        candidate,
                        observations);
        var body =
                (ObjectNode)
                        JSON.readTree(
                                EvaluationWireFixtures.forRequest(
                                        evaluationJson(
                                                "REANALYZE",
                                                "CAUSE_ANALYSIS",
                                                false,
                                                1,
                                                "[\"UNSUPPORTED_CAUSE_ANALYSIS\"]",
                                                "null"),
                                        JSON.readTree(review.request().userPrompt())));
        var activity = (ObjectNode) body.path("activity_review");
        activity.put("verdict", "UNSUPPORTED");
        activity.put("reason_code", "ACHIEVEMENT_NOT_GROUNDED");
        activity.put("explanation", "승인된 활동 목표를 충족할 근거가 부족하다.");
        var result = review.parser().parse(body.toString());
        assertThat(result.canDeliver()).isFalse();
        assertThat(result.retryTarget()).isEqualTo(RetryTarget.CAUSE_ANALYSIS);
    }

    private PreparedPrompt<AnalysisResult> analysis(GoalObservationInput supplied) {
        return prompts.causeAnalysis(
                trace(ANALYSIS_ID, null), attempts(), 1, input, null, supplied);
    }

    private String analysisBody(LlmRequest request, List<UUID> positiveGoals, boolean achieved) {
        var body = (ObjectNode) JSON.readTree(analysisJson("ADVANCE_GOAL", MG_LINK, "S1"));
        body.remove("turn_id");
        body.remove("goal_observation");
        var statuses = body.putObject("observations");
        var assessment = body.putObject("activity_goal");
        assessment.put("decision", achieved ? "ACHIEVED" : "IN_PROGRESS");
        var basis = assessment.putArray("basis");
        JSON.readTree(request.userPrompt())
                .path("analysis_observation_tasks")
                .properties()
                .forEach(
                        task -> {
                            var binding = task.getValue();
                            boolean positive =
                                    positiveGoals.contains(
                                            UUID.fromString(
                                                    binding.path("micro_goal_id").asString()));
                            statuses.put(task.getKey(), positive ? "EXPLICIT" : "NOT_EXPRESSED");
                            if (positive && achieved) {
                                var ref = basis.addObject();
                                ref.put("turn_id", request.traceContext().turnId().toString());
                                ref.set("micro_goal_id", binding.path("micro_goal_id"));
                                ref.set("criterion_reference", binding.path("criterion_reference"));
                            }
                        });
        return body.toString();
    }

    private AiCallResult<LlmCompletion> success(LlmRequest request, String content) {
        return new AiCallResult.Success<>(
                new LlmCompletion(content, "fake", "fake", "stop", null, null),
                new AiCallMetadata(
                        request.traceContext().requestId(),
                        request.operation(),
                        "fake",
                        "fake",
                        request.promptVersion(),
                        request.responseSchemaVersion(),
                        null,
                        1L,
                        request.currentAttempt(),
                        null,
                        null,
                        "STOP"));
    }
}
