package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RetryingRoleplayTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.StructuredRoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.*;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationMapper;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplayGoalObservationPromptTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final RoleplayPromptFactory factory = new RoleplayPromptFactory(json, null);
    private final RoleplayTurnInput input = input("친구가 넘어졌어.");
    private final Reference event = new Reference(TURN_ID, MG_EVENT, reference(MG_EVENT));
    private final Reference prior =
            new Reference(UUID.randomUUID(), MG_EMOTION, reference(MG_EMOTION));
    private final GoalObservationInput observations =
            com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                    .withSyntheticObservationContexts(
                            criteria(), List.of(new Observation(prior, Status.IMPLICIT)));

    @Test
    void explicitlySelectsVersionedAnalysisAndEvaluationWithApprovedReferences() {
        var analysisPrompt = analysisPrompt();
        var analysis = analysisPrompt.parser().parse(analysisJson(ANALYSIS_ID));
        var evaluationPrompt = evaluationPrompt(analysis);
        var evaluation = evaluationPrompt.parser().parse(evaluationJson(ANALYSIS_ID));
        var ready = new RoleplayTurnResult.Ready(analysis, candidate(analysis), evaluation);

        assertThat(analysisPrompt.request().promptVersion())
                .isEqualTo("roleplay-cause-analysis/v2");
        assertThat(analysisPrompt.request().responseSchemaVersion())
                .isEqualTo(GoalObservationOutput.ANALYSIS_VERSION);
        assertThat(evaluationPrompt.request().responseSchemaVersion())
                .isEqualTo(GoalObservationOutput.EVALUATION_VERSION);
        var body = json.readTree(evaluationPrompt.request().userPrompt());
        assertThat(
                        body.path("analysis_result")
                                .path("goal_observation")
                                .path("analysis_id")
                                .asString())
                .isEqualTo(ANALYSIS_ID.toString());
        assertThat(body.path("goal_observation_context").path("approved_criteria")).hasSize(3);
        assertThat(body.path("goal_observation_context").path("confirmed_observations")).hasSize(1);
        var contexts = body.path("goal_observation_context").path("confirmed_observation_contexts");
        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).path("reference").path("turn_id").asString())
                .isEqualTo(prior.turnId().toString());
        assertThat(contexts.get(0).path("input_context").path("micro_goal_id").asString())
                .isEqualTo(
                        observations
                                .confirmedObservationContexts()
                                .getFirst()
                                .inputContext()
                                .microGoalId()
                                .toString());
        assertThat(
                        contexts.get(0)
                                .path("goal_exposure_before_input")
                                .path("answer_revealed")
                                .asBoolean())
                .isFalse();
        assertThat(
                        json.readTree(analysisPrompt.request().userPrompt())
                                .path("goal_observation_context")
                                .path("confirmed_observation_contexts"))
                .isEqualTo(contexts);

        assertThat(
                        ready.analysis()
                                .goalObservation()
                                .observations()
                                .getFirst()
                                .reference()
                                .microGoalId())
                .isEqualTo(MG_EVENT);
        assertThat(ready.candidate().targetMicroGoalId()).isEqualTo(MG_EMOTION);
        assertThat(ready.analysis().goalObservation().activityGoal().basis())
                .containsExactly(event, prior);
        assertThat(analysisPrompt.request().userPrompt())
                .doesNotContain(
                        REQUEST_ID.toString(),
                        CHILD_ID.toString(),
                        SESSION_ID.toString(),
                        ACTIVITY_ID.toString());
        assertThat(analysisPrompt.request().systemPrompt())
                .contains("모든 작은 목표의 순서대로 통과나 정해진 개수", "정답 제공만으로");
    }

    @Test
    void bothPromptsReceiveTheOtherGoalsPriorAnswerRevealWithoutUsingCurrentSupport() {
        var priorResponse =
                new GoalObservationInput.PriorResponse(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        MG_EVENT,
                        1,
                        1,
                        "S3",
                        "ANSWER_REVEAL");
        var supplied = withCurrentEventExposure(priorResponse);
        var analysis =
                factory.causeAnalysis(
                        trace(ANALYSIS_ID, null), attempts(), 1, input, null, supplied);
        var result = parsedAnalysis();
        var evaluation =
                factory.responseEvaluation(
                        trace(ANALYSIS_ID, CANDIDATE_ID),
                        attempts(),
                        1,
                        input,
                        result,
                        candidate(result),
                        supplied);
        for (var request : List.of(analysis.request(), evaluation.request())) {
            var body = json.readTree(request.userPrompt());
            assertThat(body.path("state").path("current_micro_goal_id").asString())
                    .isEqualTo(MG_EMOTION.toString());
            assertThat(body.path("state").path("current_support_level").asString()).isEqualTo("S1");
            assertThat(body.path("goal_observation_context").path("current_goal_exposures"))
                    .isEqualTo(
                            json.readTree(
                                    json.writeValueAsString(supplied.currentGoalExposures())));
            assertThat(request.systemPrompt()).contains("현재 질문의 state 지원을 다른 목표에 복사하지 않는다");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sameTurn", "futureTurn"})
    void aCurrentOrFutureResponseCannotBePassedAsPriorSupport(String changed) {
        var prior =
                new GoalObservationInput.PriorResponse(
                        changed.equals("sameTurn") ? TURN_ID : UUID.randomUUID(),
                        UUID.randomUUID(),
                        MG_EVENT,
                        changed.equals("futureTurn") ? input.state().turnCount() : 1,
                        1,
                        "S3",
                        "ANSWER_REVEAL");
        var supplied = withCurrentEventExposure(prior);
        assertThatThrownBy(
                        () ->
                                factory.causeAnalysis(
                                        trace(ANALYSIS_ID, null),
                                        attempts(),
                                        1,
                                        input,
                                        null,
                                        supplied))
                .isInstanceOf(IllegalArgumentException.class);
        var analysis = parsedAnalysis();
        assertThatThrownBy(
                        () ->
                                factory.responseEvaluation(
                                        trace(ANALYSIS_ID, CANDIDATE_ID),
                                        attempts(),
                                        1,
                                        input,
                                        analysis,
                                        candidate(analysis),
                                        supplied))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PROBE_EVENT",
                "PROBE_EMOTION",
                "PROBE_CAUSE",
                "PROBE_ACTION",
                "ADVANCE_GOAL"
            })
    void anExplicitObservationDoesNotAllowProbingAnAlreadyAnsweredGoal(String strategy) {
        var supplied =
                withCurrentEventExposure(
                        new GoalObservationInput.PriorResponse(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                MG_EVENT,
                                1,
                                1,
                                "S3",
                                "ANSWER_REVEAL"));
        var body = analysisBody(ANALYSIS_ID);
        var next = object(body.get("next_strategy"));
        next.put("type", strategy);
        next.put("target_micro_goal_id", MG_EVENT);
        next.put("recommended_support_level", "S1");
        var parser =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null), attempts(), 1, input, null, supplied)
                        .parser();
        assertThatThrownBy(() -> parser.parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PROBE_EVENT",
                "PROBE_EMOTION",
                "PROBE_CAUSE",
                "PROBE_ACTION",
                "ADVANCE_GOAL"
            })
    void theSameGoalCannotBeAskedAgainAtALowerSupportLevel(String strategy) {
        var body = analysisBody(ANALYSIS_ID);
        var next = object(body.get("next_strategy"));
        next.put("type", strategy);
        next.put("target_micro_goal_id", MG_EMOTION);
        next.put("recommended_support_level", "S0");
        assertThatThrownBy(() -> analysisPrompt().parser().parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void aDifferentGoalsLowerStartingSupportAndClosingAfterAnswerRevealRemainPossible() {
        var body = analysisBody(ANALYSIS_ID);
        var next = object(body.get("next_strategy"));
        next.put("type", "ADVANCE_GOAL");
        next.put("target_micro_goal_id", MG_EVENT);
        next.put("recommended_support_level", "S0");
        assertThat(
                        analysisPrompt()
                                .parser()
                                .parse(json.writeValueAsString(body))
                                .nextStrategy()
                                .recommendedSupportLevel())
                .isEqualTo("S0");
        var supplied =
                withCurrentEventExposure(
                        new GoalObservationInput.PriorResponse(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                MG_EVENT,
                                1,
                                1,
                                "S3",
                                "ANSWER_REVEAL"));
        next.put("type", "CLOSE_ROLEPLAY");
        next.put("recommended_support_level", "S3");
        var parser =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null), attempts(), 1, input, null, supplied)
                        .parser();
        assertThat(
                        parser.parse(json.writeValueAsString(body))
                                .goalObservation()
                                .activityGoal()
                                .decision())
                .isEqualTo(ActivityDecision.IN_PROGRESS);
    }

    @Test
    void invalidSupportIsReanalyzedBeforeGeneratingAnyCandidate() {
        var provider = new ObservationProvider();
        provider.invalidSupportOnce = true;
        assertThat(execute(provider)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(provider.requests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(provider.requests.getFirst().traceContext().analysisId())
                .isNotEqualTo(provider.requests.get(1).traceContext().analysisId());
        var retry = json.readTree(provider.requests.get(1).userPrompt()).path("retry");
        assertThat(retry.path("failure_codes").get(0).asString())
                .isEqualTo("INVALID_OUTPUT_FORMAT");
        assertThat(retry.path("focus").toString())
                .contains("이미 정답을 제공한 목표를 다시 유도하지 않는다", "같은 목표의 지원을 낮춰 반복 유도하지 않는다");
    }

    @Test
    void repeatedInvalidSupportStopsAtTheExistingAnalysisLimitWithoutGeneratingACandidate() {
        var provider = new ObservationProvider();
        provider.alwaysInvalidSupport = true;
        assertThat(execute(provider)).isInstanceOf(RoleplayTurnResult.RecoveryRequired.class);
        assertThat(provider.requests)
                .hasSize(3)
                .allSatisfy(
                        request ->
                                assertThat(request.operation())
                                        .isEqualTo(AiOperation.CAUSE_ANALYSIS));
        for (var request : provider.requests.subList(1, 3)) {
            assertThat(
                            json.readTree(request.userPrompt())
                                    .path("retry")
                                    .path("failure_codes")
                                    .get(0)
                                    .asString())
                    .isEqualTo("INVALID_OUTPUT_FORMAT");
        }
    }

    private GoalObservationInput withCurrentEventExposure(
            GoalObservationInput.PriorResponse prior) {
        var exposures =
                observations.currentGoalExposures().stream()
                        .map(
                                e ->
                                        e.microGoalId().equals(MG_EVENT)
                                                ? new GoalObservationInput.CurrentGoalExposure(
                                                        MG_EVENT,
                                                        new GoalObservationInput.GoalExposure(
                                                                0, true, prior))
                                                : e)
                        .toList();
        return new GoalObservationInput(
                observations.approvedCriteria(),
                observations.confirmedObservations(),
                observations.confirmedObservationContexts(),
                exposures);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "version",
                "analysis",
                "goal",
                "criterion",
                "turn",
                "duplicate",
                "basis",
                "duplicateBasis",
                "emptyBasis",
                "status"
            })
    void rejectsInvalidObservationAnalysis(String changed) {
        var body = analysisBody(ANALYSIS_ID);
        var extension = object(body.get("goal_observation"));
        var observation = object(((List<?>) extension.get("observations")).getFirst());
        var ref = object(observation.get("reference"));
        var assessment = object(extension.get("activity_goal"));
        switch (changed) {
            case "missing" -> body.remove("goal_observation");
            case "version" -> extension.put("schema_version", "analysis-result/v1");
            case "analysis" -> extension.put("analysis_id", UUID.randomUUID().toString());
            case "goal" -> ref.put("micro_goal_id", UUID.randomUUID().toString());
            case "criterion" -> ref.put("criterion_reference", reference(MG_EMOTION));
            case "turn" -> ref.put("turn_id", UUID.randomUUID().toString());
            case "duplicate" -> extension.put("observations", List.of(observation, observation));
            case "basis" ->
                    assessment.put(
                            "basis", List.of(new Reference(TURN_ID, MG_LINK, reference(MG_LINK))));
            case "duplicateBasis" -> assessment.put("basis", List.of(event, event));
            case "emptyBasis" -> {
                assessment.put("decision", "ACHIEVED");
                assessment.put("basis", List.of());
            }
            case "status" -> observation.put("status", "UNSUPPORTED_STATUS");
            default -> throw new AssertionError();
        }
        assertThatThrownBy(() -> analysisPrompt().parser().parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void oneTurnCanObserveMultipleGoalsWithoutRequiringEveryGoal() {
        var body = analysisBody(ANALYSIS_ID);
        var extension = object(body.get("goal_observation"));
        var second = new Reference(TURN_ID, MG_EMOTION, reference(MG_EMOTION));
        extension.put(
                "observations",
                List.of(
                        new Observation(second, Status.IMPLICIT),
                        new Observation(event, Status.EXPLICIT)));
        extension.put("activity_goal", new Assessment(ActivityDecision.ACHIEVED, List.of(second)));

        var result = analysisPrompt().parser().parse(json.writeValueAsString(body));
        assertThat(result.goalObservation().observations()).hasSize(2);
        assertThat(result.goalObservation().activityGoal().decision())
                .isEqualTo(ActivityDecision.ACHIEVED);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"NOT_EXPRESSED", "MISUNDERSTOOD", "CONTRADICTORY"})
    void rejectsAchievementWithOnlyANegativeCurrentBasis(Status status) {
        var body = analysisBody(ANALYSIS_ID);
        var extension = object(body.get("goal_observation"));
        extension.put("observations", List.of(new Observation(event, status)));
        extension.put("activity_goal", new Assessment(ActivityDecision.ACHIEVED, List.of(event)));

        // 과거 긍정 관찰이 있어도 판정이 참조하지 않으면 근거가 될 수 없다.
        assertThatThrownBy(() -> analysisPrompt().parser().parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"NOT_EXPRESSED", "MISUNDERSTOOD", "CONTRADICTORY"})
    void rejectsAchievementWithOnlyANegativeConfirmedBasis(Status status) {
        var supplied =
                com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                        .withSyntheticObservationContexts(
                                criteria(), List.of(new Observation(prior, status)));
        var body = analysisBody(ANALYSIS_ID);
        object(body.get("goal_observation"))
                .put("activity_goal", new Assessment(ActivityDecision.ACHIEVED, List.of(prior)));
        var parser =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null), attempts(), 1, input, null, supplied)
                        .parser();

        // 현재 긍정 관찰도 판정 근거에서 빠져 있으면 달성을 뒷받침하지 않는다.
        assertThatThrownBy(() -> parser.parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"EXPLICIT", "IMPLICIT"})
    void acceptsAchievementWithAPositiveCurrentBasis(Status status) {
        var body = analysisBody(ANALYSIS_ID);
        var extension = object(body.get("goal_observation"));
        extension.put("observations", List.of(new Observation(event, status)));
        extension.put("activity_goal", new Assessment(ActivityDecision.ACHIEVED, List.of(event)));

        assertThat(
                        analysisPrompt()
                                .parser()
                                .parse(json.writeValueAsString(body))
                                .goalObservation()
                                .activityGoal()
                                .decision())
                .isEqualTo(ActivityDecision.ACHIEVED);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"EXPLICIT", "IMPLICIT"})
    void acceptsMixedBasisWithAPositiveConfirmedObservation(Status status) {
        var supplied =
                com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                        .withSyntheticObservationContexts(
                                criteria(), List.of(new Observation(prior, status)));
        var body = analysisBody(ANALYSIS_ID);
        var extension = object(body.get("goal_observation"));
        extension.put("observations", List.of(new Observation(event, Status.CONTRADICTORY)));
        extension.put(
                "activity_goal", new Assessment(ActivityDecision.ACHIEVED, List.of(event, prior)));
        var parser =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null), attempts(), 1, input, null, supplied)
                        .parser();

        assertThat(
                        parser.parse(json.writeValueAsString(body))
                                .goalObservation()
                                .activityGoal()
                                .basis())
                .containsExactly(event, prior);
    }

    @Test
    void unsupportedAchievementIsReanalyzedBeforeGeneratingAnyCandidate() {
        var provider = new ObservationProvider();
        provider.negativeAchievementOnce = true;
        assertThat(execute(provider)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(provider.requests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(
                        json.readTree(provider.requests.get(1).userPrompt())
                                .path("retry")
                                .path("failure_codes")
                                .get(0)
                                .asString())
                .isEqualTo("INVALID_OUTPUT_FORMAT");
    }

    @Test
    void repeatedUnsupportedAchievementStopsWithoutGeneratingACandidate() {
        var provider = new ObservationProvider();
        provider.alwaysNegativeAchievement = true;
        assertThat(execute(provider)).isInstanceOf(RoleplayTurnResult.RecoveryRequired.class);
        assertThat(provider.requests)
                .hasSize(3)
                .allSatisfy(
                        request ->
                                assertThat(request.operation())
                                        .isEqualTo(AiOperation.CAUSE_ANALYSIS));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "version",
                "analysis",
                "observations",
                "activity",
                "stringBoolean",
                "missingBoolean"
            })
    void passRequiresMatchingExplicitObservationAndGoalReview(String changed) {
        var body = evaluationBody(ANALYSIS_ID);
        var review = object(body.get("goal_observation_review"));
        switch (changed) {
            case "missing" -> body.remove("goal_observation_review");
            case "version" -> review.put("schema_version", "evaluation-result/v1");
            case "analysis" -> review.put("analysis_id", UUID.randomUUID().toString());
            case "observations" -> review.put("observations_supported", false);
            case "activity" -> review.put("activity_goal_supported", false);
            case "stringBoolean" -> review.put("observations_supported", "true");
            case "missingBoolean" -> review.remove("activity_goal_supported");
            default -> throw new AssertionError();
        }
        assertThatThrownBy(
                        () ->
                                evaluationPrompt(parsedAnalysis())
                                        .parser()
                                        .parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void unsupportedObservationsReturnToAnalysisRatherThanOnlyRegeneratingText() {
        var body = evaluationBody(ANALYSIS_ID);
        object(body.get("goal_observation_review")).put("observations_supported", false);
        body.put("decision", "REANALYZE");
        body.put("safe_to_send", false);
        body.put("retry_target", "CAUSE_ANALYSIS");
        body.put("failure_codes", List.of("UNSUPPORTED_CAUSE_ANALYSIS"));
        assertThat(
                        evaluationPrompt(parsedAnalysis())
                                .parser()
                                .parse(json.writeValueAsString(body))
                                .canDeliver())
                .isFalse();

        body.put("decision", "REGENERATE");
        body.put("retry_target", "RESPONSE_GENERATION");
        body.put(
                "revision_instruction",
                Map.of(
                        "keep",
                        List.of(),
                        "change",
                        List.of(),
                        "avoid",
                        List.of(),
                        "required",
                        List.of()));
        assertThatThrownBy(
                        () ->
                                evaluationPrompt(parsedAnalysis())
                                        .parser()
                                        .parse(json.writeValueAsString(body)))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void legacyOutputsAndMissingObservationInputsAreRejected() {
        var legacyAnalysis = analysisBody(ANALYSIS_ID);
        legacyAnalysis.remove("goal_observation");
        assertThatThrownBy(
                        () ->
                                analysisPrompt()
                                        .parser()
                                        .parse(json.writeValueAsString(legacyAnalysis)))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(
                        () ->
                                factory.causeAnalysis(
                                        trace(ANALYSIS_ID, null), attempts(), 1, input, null, null))
                .isInstanceOf(NullPointerException.class);
        var legacyReview = evaluationBody(ANALYSIS_ID);
        legacyReview.remove("goal_observation_review");
        assertThatThrownBy(
                        () ->
                                evaluationPrompt(parsedAnalysis())
                                        .parser()
                                        .parse(json.writeValueAsString(legacyReview)))
                .isInstanceOf(InvalidLlmOutputException.class);
        var legacyEvaluation =
                new EvaluationResult(
                        CANDIDATE_ID,
                        true,
                        com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision
                                .PASS,
                        null,
                        0,
                        List.of(),
                        null);
        assertThatThrownBy(
                        () ->
                                new RoleplayTurnResult.Ready(
                                        parsedAnalysis(),
                                        candidate(parsedAnalysis()),
                                        legacyEvaluation))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readyRejectsReviewOfAnotherAnalysisEvenWithoutAParser() {
        var review = evaluationPrompt(parsedAnalysis()).parser().parse(evaluationJson(ANALYSIS_ID));
        var wrong =
                new EvaluationResult(
                        review.candidateId(),
                        true,
                        review.decision(),
                        null,
                        0,
                        List.of(),
                        null,
                        new Review(
                                GoalObservationOutput.EVALUATION_VERSION,
                                UUID.randomUUID(),
                                true,
                                true));
        assertThatThrownBy(
                        () ->
                                new RoleplayTurnResult.Ready(
                                        parsedAnalysis(), candidate(parsedAnalysis()), wrong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missingCriterion",
                "foreignGoal",
                "description",
                "duplicateCriterion",
                "foreignPrior",
                "duplicatePrior",
                "currentPrior"
            })
    void invalidServerObservationContextIsRejectedBeforeCallingAi(String changed) {
        var criteria = new ArrayList<>(criteria());
        var history = new ArrayList<>(observations.confirmedObservations());
        switch (changed) {
            case "missingCriterion" -> criteria.removeFirst();
            case "foreignGoal" ->
                    criteria.add(
                            new GoalObservationInput.Criterion(
                                    UUID.randomUUID(), "foreign", "foreign"));
            case "description" ->
                    criteria.set(
                            0,
                            new GoalObservationInput.Criterion(
                                    criteria.getFirst().microGoalId(),
                                    criteria.getFirst().reference(),
                                    "승인되지 않은 설명"));
            case "duplicateCriterion" -> criteria.add(criteria.getFirst());
            case "foreignPrior" ->
                    history.add(
                            new Observation(
                                    new Reference(UUID.randomUUID(), MG_EVENT, "unknown"),
                                    Status.EXPLICIT));
            case "duplicatePrior" -> history.add(history.getFirst());
            case "currentPrior" -> history.add(new Observation(event, Status.EXPLICIT));
            default -> throw new AssertionError();
        }
        assertThatThrownBy(
                        () ->
                                factory.causeAnalysis(
                                        trace(ANALYSIS_ID, null),
                                        attempts(),
                                        1,
                                        input,
                                        null,
                                        com.neuringo.neuringobe.roleplay.application
                                                .RoleplayObservationTestPlans
                                                .withSyntheticObservationContexts(
                                                        criteria, history)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalObservationTurnUsesOnlyTheExistingThreeCalls() {
        var provider = new ObservationProvider();
        var result = execute(provider);
        assertThat(result).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(provider.requests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(provider.requests)
                .extracting(LlmRequest::responseSchemaVersion)
                .containsExactly(
                        "analysis-result/v2", "candidate-response/v1", "evaluation-result/v2");
        var ready = (RoleplayTurnResult.Ready) result;
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        provider.requests.getFirst().traceContext(),
                        7,
                        3,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        new RoleplayTurnResult.SpokenReady(
                                ready, input.learnerTurn().canonicalUtterance(), null));
        var context =
                new RoleplayGoalObservationPlan.Context(
                        new RoleplayGoalObservationPlan.Scope(
                                command.sessionId(),
                                command.scenarioId(),
                                command.scenarioVersion(),
                                "test-approval",
                                command.turnId(),
                                command.candidateId(),
                                command.expectedVersion()),
                        Map.of(
                                MG_EVENT,
                                Set.of(reference(MG_EVENT)),
                                MG_EMOTION,
                                Set.of(reference(MG_EMOTION)),
                                MG_LINK,
                                Set.of(reference(MG_LINK))),
                        List.of(
                                new RoleplayGoalObservationPlan.Observation(
                                        new RoleplayGoalObservationPlan.EvidenceReference(
                                                prior.turnId(),
                                                prior.microGoalId(),
                                                prior.criterionReference()),
                                        RoleplayGoalObservationPlan.ObservationStatus.IMPLICIT)),
                        GoalObservationOutput.ANALYSIS_VERSION,
                        GoalObservationOutput.EVALUATION_VERSION);
        var plan = RoleplayGoalObservationMapper.toPlan(context, command, ready);
        assertThat(plan.proposal().observations())
                .extracting(o -> o.reference().microGoalId())
                .containsExactly(MG_EVENT);
        assertThat(plan.proposal().nextFocusGoalId()).isEqualTo(MG_EMOTION);
        assertThat(plan.proposal().decision())
                .isEqualTo(
                        com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator
                                .Decision.IN_PROGRESS);
    }

    @Test
    void evaluationTimeoutRetriesTheSameObservationAndCandidateWithoutRepeatingAnalysis() {
        var provider = new ObservationProvider();
        provider.timeoutEvaluation = true;
        assertThat(execute(provider)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(provider.requests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(provider.requests.get(2).userPrompt())
                .isEqualTo(provider.requests.get(3).userPrompt());
        assertThat(provider.requests.get(2).traceContext())
                .isEqualTo(provider.requests.get(3).traceContext());
    }

    @Test
    void rejectedObservationsAreReanalyzedWithANewAnalysisId() {
        var provider = new ObservationProvider();
        provider.reanalyzeOnce = true;
        assertThat(execute(provider)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(provider.requests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(provider.requests.get(0).traceContext().analysisId())
                .isNotEqualTo(provider.requests.get(3).traceContext().analysisId());
        assertThat(provider.requests.get(3).traceContext().analysisId())
                .isEqualTo(provider.requests.get(5).traceContext().analysisId());
    }

    private RoleplayTurnResult execute(ObservationProvider provider) {
        return new RetryingRoleplayTurnExecutor()
                .execute(
                        TURN_ID,
                        new StructuredRoleplayTurnSteps(
                                factory,
                                new StructuredLlmExecutor(provider),
                                trace(null, null),
                                input,
                                UUID::randomUUID,
                                observations));
    }

    private PreparedPrompt<AnalysisResult> analysisPrompt() {
        return factory.causeAnalysis(
                trace(ANALYSIS_ID, null), attempts(), 1, input, null, observations);
    }

    private AnalysisResult parsedAnalysis() {
        return analysisPrompt().parser().parse(analysisJson(ANALYSIS_ID));
    }

    private CandidateResponse candidate(AnalysisResult analysis) {
        return factory.responseGeneration(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        input,
                        analysis,
                        CANDIDATE_ID,
                        null)
                .parser()
                .parse(candidateJson("친구는 어떤 마음일까?", "GUIDING_QUESTION"));
    }

    private PreparedPrompt<EvaluationResult> evaluationPrompt(AnalysisResult analysis) {
        return factory.responseEvaluation(
                trace(ANALYSIS_ID, CANDIDATE_ID),
                attempts(),
                1,
                input,
                analysis,
                candidate(analysis),
                observations);
    }

    private List<GoalObservationInput.Criterion> criteria() {
        return input.scenario().microGoals().stream()
                .flatMap(
                        g ->
                                g.requiredEvidence().stream()
                                        .map(
                                                e ->
                                                        new GoalObservationInput.Criterion(
                                                                g.id(), reference(g.id()), e)))
                .toList();
    }

    private String reference(UUID goalId) {
        return "approved-" + goalId;
    }

    private String analysisJson(UUID analysisId) {
        return json.writeValueAsString(analysisBody(analysisId));
    }

    private Map<String, Object> analysisBody(UUID analysisId) {
        var body =
                object(
                        json.readValue(
                                RoleplayFixtures.analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"),
                                Map.class));
        body.put(
                "goal_observation",
                json.readValue(
                        json.writeValueAsString(
                                new Analysis(
                                        GoalObservationOutput.ANALYSIS_VERSION,
                                        analysisId,
                                        List.of(new Observation(event, Status.EXPLICIT)),
                                        new Assessment(
                                                ActivityDecision.IN_PROGRESS,
                                                List.of(event, prior)))),
                        Map.class));
        return body;
    }

    private String evaluationJson(UUID analysisId) {
        return json.writeValueAsString(evaluationBody(analysisId));
    }

    private Map<String, Object> evaluationBody(UUID analysisId) {
        var body =
                object(
                        json.readValue(
                                RoleplayFixtures.evaluationJson(
                                        "PASS", null, true, 0, "[]", "null"),
                                Map.class));
        body.put(
                "goal_observation_review",
                new HashMap<>(
                        Map.of(
                                "schema_version",
                                GoalObservationOutput.EVALUATION_VERSION,
                                "analysis_id",
                                analysisId.toString(),
                                "observations_supported",
                                true,
                                "activity_goal_supported",
                                true)));
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private class ObservationProvider implements LlmProvider {
        private final List<LlmRequest> requests = new ArrayList<>();
        private boolean timeoutEvaluation;
        private boolean reanalyzeOnce;
        private boolean invalidSupportOnce;
        private boolean alwaysInvalidSupport;
        private boolean negativeAchievementOnce;
        private boolean alwaysNegativeAchievement;

        @Override
        public AiCallResult<LlmCompletion> complete(LlmRequest request) {
            requests.add(request);
            var metadata =
                    new AiCallMetadata(
                            request.traceContext().requestId(),
                            request.operation(),
                            "fake",
                            "fake",
                            request.promptVersion(),
                            request.responseSchemaVersion(),
                            null,
                            0,
                            request.currentAttempt(),
                            null,
                            null,
                            "stop");
            if (timeoutEvaluation && request.operation() == AiOperation.RESPONSE_EVALUATION) {
                timeoutEvaluation = false;
                return new AiCallResult.Failure<>(
                        new AiFailure(AiFailureType.TIMEOUT, null), metadata);
            }
            if (reanalyzeOnce && request.operation() == AiOperation.RESPONSE_EVALUATION) {
                reanalyzeOnce = false;
                var failed = evaluationBody(request.traceContext().analysisId());
                failed.put("candidate_id", request.traceContext().candidateId());
                failed.put("decision", "REANALYZE");
                failed.put("safe_to_send", false);
                failed.put("retry_target", "CAUSE_ANALYSIS");
                failed.put("failure_codes", List.of("UNSUPPORTED_CAUSE_ANALYSIS"));
                object(failed.get("goal_observation_review")).put("observations_supported", false);
                return new AiCallResult.Success<>(
                        new LlmCompletion(
                                json.writeValueAsString(failed),
                                "fake",
                                "fake",
                                "stop",
                                null,
                                null),
                        metadata);
            }
            if ((invalidSupportOnce || alwaysInvalidSupport)
                    && request.operation() == AiOperation.CAUSE_ANALYSIS) {
                invalidSupportOnce = false;
                var body = analysisBody(request.traceContext().analysisId());
                object(body.get("next_strategy")).put("recommended_support_level", "S0");
                return new AiCallResult.Success<>(
                        new LlmCompletion(
                                json.writeValueAsString(body), "fake", "fake", "stop", null, null),
                        metadata);
            }
            if ((negativeAchievementOnce || alwaysNegativeAchievement)
                    && request.operation() == AiOperation.CAUSE_ANALYSIS) {
                negativeAchievementOnce = false;
                var body = analysisBody(request.traceContext().analysisId());
                var extension = object(body.get("goal_observation"));
                extension.put(
                        "observations", List.of(new Observation(event, Status.MISUNDERSTOOD)));
                extension.put(
                        "activity_goal", new Assessment(ActivityDecision.ACHIEVED, List.of(event)));
                return new AiCallResult.Success<>(
                        new LlmCompletion(
                                json.writeValueAsString(body), "fake", "fake", "stop", null, null),
                        metadata);
            }
            var content =
                    switch (request.operation()) {
                        case CAUSE_ANALYSIS -> analysisJson(request.traceContext().analysisId());
                        case RESPONSE_GENERATION ->
                                candidateJson("친구는 어떤 마음일까?", "GUIDING_QUESTION")
                                        .replace(
                                                CANDIDATE_ID.toString(),
                                                request.traceContext().candidateId().toString());
                        case RESPONSE_EVALUATION ->
                                evaluationJson(request.traceContext().analysisId())
                                        .replace(
                                                CANDIDATE_ID.toString(),
                                                request.traceContext().candidateId().toString());
                        default -> throw new AssertionError();
                    };
            return new AiCallResult.Success<>(
                    new LlmCompletion(content, "fake", "fake", "stop", null, null), metadata);
        }
    }
}
