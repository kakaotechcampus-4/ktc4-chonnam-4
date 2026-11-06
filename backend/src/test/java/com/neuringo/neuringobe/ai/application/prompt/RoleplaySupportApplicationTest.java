package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.*;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplaySupportApplicationTest {
    private final UUID first = UUID.randomUUID(),
            current = UUID.randomUUID(),
            next = UUID.randomUUID();
    private final UUID priorTurn = UUID.randomUUID(),
            turn = UUID.randomUUID(),
            analysisId = UUID.randomUUID();
    private final Reference prior = new Reference(priorTurn, first, "a");
    private final List<Observation> observed =
            List.of(new Observation(new Reference(turn, current, "b"), Status.EXPLICIT));
    private final JsonMapper json = JsonMapper.builder().build();
    private final RoleplayTurnInput.State state =
            new RoleplayTurnInput.State(current, "S0", 1, 1, false, 2, 20, 9);

    @ParameterizedTest
    @CsvSource({"S0,S0,AT_MINIMUM", "S1,S0,DECREASED", "S2,S1,DECREASED", "S3,S2,DECREASED"})
    void secondAchievementAppliesOneStepBeforeGeneratingTheNextUnvisitedGoal(
            String base, String applied, String kind) {
        var raw = analysis(base, "GOAL_ACHIEVED", next);
        var result = RoleplaySupportApplication.apply(state, observations(false), raw);
        assertThat(result.nextStrategy().recommendedSupportLevel()).isEqualTo(applied);
        assertThat(raw.nextStrategy().recommendedSupportLevel()).isEqualTo(base);
        assertThat(result.supportAdjustment().baseSupportLevel()).isEqualTo(base);
        assertThat(result.supportAdjustment().kind()).isEqualTo(kind);
        assertThat(result.supportAdjustment().basis())
                .hasSize(2)
                .extracting(e -> e.microGoalId())
                .containsExactly(current, first);
        assertThat(result.supportAdjustment().basis().getFirst().turnId()).isEqualTo(turn);
        RoleplaySupportDecisionRules.requireValidAnalysis(state, observations(false), result);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ADVANCE_GOAL",
                "PROBE_EVENT",
                "PROBE_EMOTION",
                "PROBE_CAUSE",
                "PROBE_ACTION"
            })
    void changingTheQuestionStrategyNameCannotBypassTheNextGoalPolicy(String strategy) {
        var raw = analysis("S2", "GOAL_ACHIEVED", next);
        var question =
                new AnalysisResult(
                        raw.turnId(),
                        raw.responseAct(),
                        raw.relevance(),
                        raw.learningState(),
                        raw.primaryGap(),
                        new AnalysisResult.NextStrategy(strategy, next, null, "S2"),
                        raw.analysisConfidence(),
                        raw.goalObservation());
        assertThat(
                        RoleplaySupportApplication.apply(state, observations(false), question)
                                .nextStrategy()
                                .recommendedSupportLevel())
                .isEqualTo("S1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRM_UNDERSTANDING", "REDIRECT_TO_SCENARIO"})
    void aControlStrategyCannotBeUsedToStartAnotherGoalAfterAchievement(String strategy) {
        var raw = analysis("S2", "GOAL_ACHIEVED", next);
        var question =
                new AnalysisResult(
                        raw.turnId(),
                        raw.responseAct(),
                        raw.relevance(),
                        raw.learningState(),
                        raw.primaryGap(),
                        new AnalysisResult.NextStrategy(strategy, next, null, "S2"),
                        raw.analysisConfidence(),
                        raw.goalObservation());
        assertThatThrownBy(
                        () ->
                                RoleplaySupportDecisionRules.requireValidModelAnalysis(
                                        state, observations(false), question))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alreadyExposedTargetDoesNotReceiveARetroactiveDecrease() {
        var raw = analysis("S2", "GOAL_ACHIEVED", next);
        assertThat(RoleplaySupportApplication.apply(state, observations(true), raw)).isSameAs(raw);
    }

    @Test
    void sameGoalAndInProgressDoNotReceiveTheNextGoalAdjustment() {
        var same = analysis("S0", "GOAL_ACHIEVED", current);
        assertThat(RoleplaySupportApplication.apply(state, observations(false), same))
                .isSameAs(same);
        var partial = analysis("S2", "PARTIAL_UNDERSTANDING", next);
        assertThat(RoleplaySupportApplication.apply(state, observations(false), partial))
                .isSameAs(partial);
    }

    @Test
    void missingPolicyRemainsUnapplied() {
        var given = observations(false);
        var unavailable =
                new GoalObservationInput(
                        given.approvedCriteria(),
                        given.confirmedObservations(),
                        given.confirmedObservationContexts(),
                        given.currentGoalExposures());
        var raw = analysis("S2", "GOAL_ACHIEVED", next);
        assertThat(RoleplaySupportApplication.apply(state, unavailable, raw)).isSameAs(raw);
    }

    @Test
    void policyCannotBeAppliedTwiceOrSilentlyRemovedAfterGeneration() {
        var raw = analysis("S2", "GOAL_ACHIEVED", next);
        var adjusted = RoleplaySupportApplication.apply(state, observations(false), raw);
        assertThatThrownBy(
                        () ->
                                RoleplaySupportApplication.apply(
                                        state, observations(false), adjusted))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RoleplaySupportApplication.requireValid(
                                        state, observations(false), raw))
                .isInstanceOf(IllegalArgumentException.class);
        var lost =
                new AnalysisResult(
                        adjusted.turnId(),
                        adjusted.responseAct(),
                        adjusted.relevance(),
                        adjusted.learningState(),
                        adjusted.primaryGap(),
                        adjusted.nextStrategy(),
                        adjusted.analysisConfidence(),
                        adjusted.goalObservation());
        assertThatThrownBy(
                        () ->
                                RoleplaySupportApplication.requireValid(
                                        state, observations(false), lost))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelCannotSupplyTheServerAdjustmentAndBothFollowingPromptsUseTheAdjustedLevel() {
        var factory = new RoleplayPromptFactory(json, null);
        var input = input();
        var trace = trace(null);
        var prompt =
                factory.causeAnalysis(
                        trace, new AiAttemptContext(0, 0, 0), 1, input, null, observations(false));
        var body =
                (Map<String, Object>)
                        json.readValue(
                                json.writeValueAsString(analysis("S2", "GOAL_ACHIEVED", next)),
                                Map.class);
        body.put(
                "supportAdjustment", Map.of("baseSupportLevel", "S3", "appliedSupportLevel", "S0"));
        body.put("server_support_adjustment", Map.of("policy_version", "forged"));
        var adjusted = prompt.parser().parse(json.writeValueAsString(body));
        assertThat(adjusted.nextStrategy().recommendedSupportLevel()).isEqualTo("S1");
        assertThat(adjusted.supportAdjustment().policyVersion()).isEqualTo("SUPPORT_DECREASE_V1");
        UUID candidateId = UUID.randomUUID();
        var candidate =
                new CandidateResponse(
                        candidateId,
                        turn,
                        "합성 질문?",
                        "GUIDING_QUESTION",
                        "ADVANCE_GOAL",
                        next,
                        "S1",
                        List.of());
        var generation =
                factory.responseGeneration(
                        trace(candidateId),
                        new AiAttemptContext(1, 0, 0),
                        1,
                        input,
                        adjusted,
                        candidateId,
                        null);
        var evaluation =
                factory.responseEvaluation(
                        trace(candidateId),
                        new AiAttemptContext(1, 1, 0),
                        1,
                        input,
                        adjusted,
                        candidate,
                        observations(false));
        for (var request : List.of(generation.request(), evaluation.request())) {
            var section = json.readTree(request.userPrompt()).path("analysis_result");
            assertThat(section.path("next_strategy").path("recommended_support_level").asString())
                    .isEqualTo("S1");
            assertThat(
                            section.path("server_support_adjustment")
                                    .path("base_support_level")
                                    .asString())
                    .isEqualTo("S2");
            assertThat(
                            section.path("server_support_adjustment")
                                    .path("applied_support_level")
                                    .asString())
                    .isEqualTo("S1");
            assertThat(
                            section.path("server_support_adjustment")
                                    .path("basis")
                                    .get(0)
                                    .path("micro_goal_id")
                                    .asString())
                    .isEqualTo(current.toString());
            assertThat(
                            section.path("server_support_adjustment")
                                    .path("basis")
                                    .get(0)
                                    .path("turn_id")
                                    .asString())
                    .isEqualTo(turn.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_ASSESSABLE", "IN_PROGRESS"})
    void interruptionImmediatelyBeforeTheCurrentAchievementUsesTheSelectedResetRule(
            String outcome) {
        var given = observations(false);
        var events = new java.util.ArrayList<>(given.supportAdjustment().confirmedInputs());
        events.add(
                new RoleplaySupportAdjustmentContext.Event(
                        UUID.randomUUID(),
                        current,
                        2,
                        2,
                        RoleplaySupportAdjustmentContext.Outcome.valueOf(outcome),
                        outcome.equals("NOT_ASSESSABLE") ? "NOT_ASSESSABLE" : "HELP_NEEDED",
                        List.of()));
        var policy =
                new RoleplaySupportAdjustmentContext(
                        RoleplaySupportAdjustmentContext.Status.NOT_ELIGIBLE,
                        "SUPPORT_DECREASE_V1",
                        2,
                        "RESET_SEQUENCE",
                        1,
                        given.supportAdjustment().basis(),
                        events);
        var supplied =
                new GoalObservationInput(
                        given.approvedCriteria(),
                        given.confirmedObservations(),
                        given.confirmedObservationContexts(),
                        given.currentGoalExposures(),
                        policy);
        var after = new RoleplayTurnInput.State(current, "S0", 2, 1, false, 3, 20, 9);
        var result =
                RoleplaySupportApplication.apply(
                        after, supplied, analysis("S2", "GOAL_ACHIEVED", next));
        assertThat(result.nextStrategy().recommendedSupportLevel())
                .isEqualTo(outcome.equals("NOT_ASSESSABLE") ? "S1" : "S2");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void
            adjustedCandidateIsIndependentlyReviewedAndRejectedAnalysisIsRecomputedWithinTheExistingBudget(
                    boolean approved) {
        var requests = new java.util.ArrayList<LlmRequest>();
        com.neuringo.neuringobe.ai.application.port.LlmProvider provider =
                request -> {
                    requests.add(request);
                    var body = json.readTree(request.userPrompt());
                    Object output;
                    if (request.operation() == AiOperation.CAUSE_ANALYSIS) {
                        var raw = analysis("S2", "GOAL_ACHIEVED", next);
                        output =
                                new AnalysisResult(
                                        raw.turnId(),
                                        raw.responseAct(),
                                        raw.relevance(),
                                        raw.learningState(),
                                        raw.primaryGap(),
                                        raw.nextStrategy(),
                                        raw.analysisConfidence(),
                                        new Analysis(
                                                raw.goalObservation().schemaVersion(),
                                                request.traceContext().analysisId(),
                                                raw.goalObservation().observations(),
                                                raw.goalObservation().activityGoal()));
                    } else if (request.operation() == AiOperation.RESPONSE_GENERATION) {
                        var level =
                                body.path("analysis_result")
                                        .path("next_strategy")
                                        .path("recommended_support_level")
                                        .asString();
                        assertThat(level).isEqualTo("S1");
                        assertThat(
                                        body.path("analysis_result")
                                                .path("server_support_adjustment")
                                                .path("base_support_level")
                                                .asString())
                                .isEqualTo("S2");
                        output =
                                new CandidateResponse(
                                        request.traceContext().candidateId(),
                                        turn,
                                        "합성 질문?",
                                        "GUIDING_QUESTION",
                                        "ADVANCE_GOAL",
                                        next,
                                        level,
                                        List.of());
                    } else {
                        assertThat(body.path("candidate_response").path("support_level").asString())
                                .isEqualTo("S1");
                        output =
                                new EvaluationResult(
                                        request.traceContext().candidateId(),
                                        approved,
                                        approved
                                                ? EvaluationDecision.PASS
                                                : EvaluationDecision.REANALYZE,
                                        approved ? null : RetryTarget.CAUSE_ANALYSIS,
                                        0,
                                        approved
                                                ? List.of()
                                                : List.of("UNSUPPORTED_CAUSE_ANALYSIS"),
                                        null,
                                        new Review(
                                                GoalObservationOutput.EVALUATION_VERSION,
                                                request.traceContext().analysisId(),
                                                approved,
                                                true));
                    }
                    var metadata =
                            new AiCallMetadata(
                                    request.traceContext().requestId(),
                                    request.operation(),
                                    "synthetic",
                                    "synthetic",
                                    request.promptVersion(),
                                    request.responseSchemaVersion(),
                                    request.policyVersion(),
                                    1,
                                    request.currentAttempt(),
                                    null,
                                    null,
                                    "stop");
                    return new AiCallResult.Success<>(
                            new LlmCompletion(
                                    json.writeValueAsString(output),
                                    "synthetic",
                                    "synthetic",
                                    "stop",
                                    null,
                                    null),
                            metadata);
                };
        var steps =
                new com.neuringo.neuringobe.ai.application.roleplay.StructuredRoleplayTurnSteps(
                        new RoleplayPromptFactory(json, null),
                        new com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor(
                                provider),
                        trace(null),
                        input(),
                        observations(false));
        var result =
                new com.neuringo.neuringobe.ai.application.roleplay.RetryingRoleplayTurnExecutor()
                        .execute(turn, steps);
        assertThat(requests).hasSize(approved ? 3 : 9);
        if (approved) {
            assertThat(result)
                    .isInstanceOfSatisfying(
                            com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.Ready
                                    .class,
                            ready ->
                                    assertThat(
                                                    ready.analysis()
                                                            .supportAdjustment()
                                                            .appliedSupportLevel())
                                            .isEqualTo("S1"));
        } else {
            assertThat(result)
                    .isInstanceOf(
                            com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult
                                    .RecoveryRequired.class);
            assertThat(
                            requests.stream()
                                    .filter(r -> r.operation() == AiOperation.CAUSE_ANALYSIS)
                                    .map(r -> r.traceContext().analysisId())
                                    .distinct()
                                    .count())
                    .isEqualTo(3);
        }
    }

    @ParameterizedTest
    @CsvSource({"S0,S1", "S1,S2", "S2,S3", "S3,S3"})
    void sameGoalAllowsOnlyOneStepOrMaintenance(String before, String after) {
        var inputState = new RoleplayTurnInput.State(current, before, 9, 1, false, 2, 20, 9);
        RoleplaySupportDecisionRules.requireValidModelAnalysis(
                inputState, observations(false), analysis(after, "HELP_NEEDED", current));
    }

    @ParameterizedTest
    @CsvSource({"S0,S2", "S0,S3", "S1,S3"})
    void reachingTheSupportTurnLimitDoesNotAllowSkippingLevels(String before, String after) {
        var inputState = new RoleplayTurnInput.State(current, before, 9, 1, false, 2, 20, 9);
        assertThatThrownBy(
                        () ->
                                RoleplaySupportDecisionRules.requireValidModelAnalysis(
                                        inputState,
                                        observations(false),
                                        analysis(after, "HELP_NEEDED", current)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("한 단계");
    }

    @Test
    void anUnassessableInputCannotIncreaseSupportEvenAtTheTurnLimit() {
        var inputState = new RoleplayTurnInput.State(current, "S0", 9, 1, false, 2, 20, 9);
        assertThatThrownBy(
                        () ->
                                RoleplaySupportDecisionRules.requireValidModelAnalysis(
                                        inputState,
                                        observations(false),
                                        analysis("S1", "NOT_ASSESSABLE", current)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("판정 불가");
    }

    @Test
    void sessionCapUsesLearningTurnsRatherThanStoredInputOrdinals() {
        var counted = new RoleplayTurnInput.State(current, "S0", 1, 1, false, 20, 3, 9, 1, 2);
        RoleplaySupportDecisionRules.requireValidModelAnalysis(
                counted, observations(false), analysis("S1", "HELP_NEEDED", current));
        var unknown = new RoleplayTurnInput.State(current, "S0", 1, 1, false, 20, 3, 9, 1, null);
        RoleplaySupportDecisionRules.requireValidModelAnalysis(
                unknown, observations(false), analysis("S1", "HELP_NEEDED", current));
    }

    @Test
    void sessionCapRequiresClosingAndCannotChangeGoalOrSupportWhileClosing() {
        var counted = new RoleplayTurnInput.State(current, "S0", 1, 1, false, 20, 3, 9, 1, 3);
        assertThatThrownBy(
                        () ->
                                RoleplaySupportDecisionRules.requireValidModelAnalysis(
                                        counted,
                                        observations(false),
                                        analysis("S1", "HELP_NEEDED", current)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("전체 학습 턴 상한");
        for (var target : List.of(current, next)) {
            for (var support : List.of("S0", "S1")) {
                var raw = analysis(support, "HELP_NEEDED", target);
                var closing =
                        new AnalysisResult(
                                raw.turnId(),
                                raw.responseAct(),
                                raw.relevance(),
                                raw.learningState(),
                                raw.primaryGap(),
                                new AnalysisResult.NextStrategy(
                                        "CLOSE_ROLEPLAY", target, null, support),
                                raw.analysisConfidence(),
                                raw.goalObservation());
                if (target.equals(current) && support.equals("S0")) {
                    RoleplaySupportDecisionRules.requireValidModelAnalysis(
                            counted, observations(false), closing);
                } else {
                    assertThatThrownBy(
                                    () ->
                                            RoleplaySupportDecisionRules.requireValidModelAnalysis(
                                                    counted, observations(false), closing))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
        }
    }

    @Test
    void currentUnassessableInputDoesNotTriggerSessionCap() {
        var counted = new RoleplayTurnInput.State(current, "S0", 1, 1, false, 20, 3, 9, 1, 3);
        RoleplaySupportDecisionRules.requireValidModelAnalysis(
                counted, observations(false), analysis("S0", "NOT_ASSESSABLE", current));
    }

    private AnalysisResult analysis(String support, String learning, UUID target) {
        return new AnalysisResult(
                turn,
                "ANSWER_ATTEMPT",
                "RELEVANT",
                learning,
                new AnalysisResult.PrimaryGap("NONE", null),
                new AnalysisResult.NextStrategy("ADVANCE_GOAL", target, null, support),
                0.9,
                new Analysis(
                        GoalObservationOutput.ANALYSIS_VERSION,
                        analysisId,
                        observed,
                        new Assessment(ActivityDecision.IN_PROGRESS, List.of())));
    }

    private GoalObservationInput observations(boolean visited) {
        var criteria =
                List.of(
                        new GoalObservationInput.Criterion(first, "a", "사건"),
                        new GoalObservationInput.Criterion(current, "b", "감정"),
                        new GoalObservationInput.Criterion(next, "c", "이유"));
        var event =
                new RoleplaySupportAdjustmentContext.Event(
                        priorTurn,
                        first,
                        1,
                        1,
                        RoleplaySupportAdjustmentContext.Outcome.ACHIEVED_WITHOUT_HINT,
                        "GOAL_ACHIEVED",
                        List.of(prior));
        var priorResponse =
                new GoalObservationInput.PriorResponse(
                        priorTurn, UUID.randomUUID(), current, 1, 1, "S0", "GUIDING_QUESTION");
        var exposures =
                List.of(
                        new GoalObservationInput.CurrentGoalExposure(
                                first, new GoalObservationInput.GoalExposure(0, false, null, "S0")),
                        new GoalObservationInput.CurrentGoalExposure(
                                current,
                                new GoalObservationInput.GoalExposure(
                                        1, false, priorResponse, "S0")),
                        new GoalObservationInput.CurrentGoalExposure(
                                next,
                                new GoalObservationInput.GoalExposure(
                                        0, false, null, visited ? "S1" : null)));
        return new GoalObservationInput(
                criteria,
                List.of(new Observation(prior, Status.EXPLICIT)),
                List.of(
                        new GoalObservationInput.ObservationContext(
                                prior,
                                UUID.randomUUID(),
                                1,
                                1,
                                new RoleplayInputContext(first, "S0", 1, 0, false),
                                new GoalObservationInput.GoalExposure(0, false, null, "S0"))),
                exposures,
                new RoleplaySupportAdjustmentContext(
                        RoleplaySupportAdjustmentContext.Status.NOT_ELIGIBLE,
                        "SUPPORT_DECREASE_V1",
                        2,
                        "RESET_SEQUENCE",
                        1,
                        List.of(event),
                        List.of(event)));
    }

    private RoleplayTurnInput input() {
        return new RoleplayTurnInput(
                new RoleplayTurnInput.Scenario(
                        "합성 학습 목표",
                        "L1",
                        List.of("합성 상황"),
                        List.of(),
                        List.of(
                                new RoleplayTurnInput.MicroGoal(
                                        first, "사건", List.of("사건"), "ACHIEVED"),
                                new RoleplayTurnInput.MicroGoal(
                                        current, "감정", List.of("감정"), "IN_PROGRESS"),
                                new RoleplayTurnInput.MicroGoal(
                                        next, "이유", List.of("이유"), "NOT_OBSERVED"))),
                state,
                new RoleplayTurnInput.LearnerTurn(turn, "합성 표준 발화"),
                List.of());
    }

    private AiTraceContext trace(UUID candidate) {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                turn,
                analysisId,
                candidate);
    }
}
