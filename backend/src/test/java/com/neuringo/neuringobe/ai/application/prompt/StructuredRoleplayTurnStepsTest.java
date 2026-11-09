package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RetryingRoleplayTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.StructuredRoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StructuredRoleplayTurnStepsTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ScriptedProvider provider = new ScriptedProvider();
    private final RoleplayTurnInput input = RoleplayFixtures.input("친구가 울고 있어");
    private final AiTraceContext trace = RoleplayFixtures.trace(null, null);
    private final StructuredRoleplayTurnSteps steps =
            new StructuredRoleplayTurnSteps(
                    new RoleplayPromptFactory(mapper, null),
                    new StructuredLlmExecutor(provider),
                    trace,
                    input,
                    RoleplayObservationTestPlans.inputForScenario((input).scenario()));
    private final RetryingRoleplayTurnExecutor executor = new RetryingRoleplayTurnExecutor();

    @Test
    void realPromptsAndStrictParsersConnectThreeStagesWithOneSharedInput() {
        var result = (RoleplayTurnResult.Ready) executor.execute(RoleplayFixtures.TURN_ID, steps);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(result.candidate().candidateId())
                .isEqualTo(provider.requests.get(1).traceContext().candidateId());
        assertThat(provider.requests.get(2).traceContext().candidateId())
                .isEqualTo(result.candidate().candidateId());
        assertThat(provider.requests.stream().map(r -> r.traceContext().analysisId()).distinct())
                .hasSize(1);
        assertThat(provider.requests.get(0).promptVersion())
                .isEqualTo(RoleplayPromptFactory.CAUSE_ANALYSIS_PROMPT_VERSION);
        assertThat(provider.requests.get(1).responseSchemaVersion())
                .isEqualTo(RoleplayPromptFactory.CANDIDATE_SCHEMA_VERSION);
        for (var request : provider.requests) {
            var body = body(request);
            assertThat(body.get("learner_turn").get("canonical_utterance").asText())
                    .isEqualTo("친구가 울고 있어");
            assertThat(body.get("scenario"))
                    .isEqualTo(body(provider.requests.getFirst()).get("scenario"));
            assertThat(body.get("recent_dialogue"))
                    .isEqualTo(body(provider.requests.getFirst()).get("recent_dialogue"));
            assertThat(request.userPrompt())
                    .doesNotContain(
                            trace.requestId().toString(),
                            trace.childId().toString(),
                            trace.sessionId().toString());
        }
    }

    @Test
    void evaluatorTechnicalRetryRebuildsSamePayloadAndPreservesCandidateAndAnalysisIds() {
        provider.timeoutOnce = AiOperation.RESPONSE_EVALUATION;
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        var first = provider.requests.get(2);
        var retry = provider.requests.get(3);
        assertThat(first.userPrompt()).isEqualTo(retry.userPrompt());
        assertThat(first.traceContext()).isEqualTo(retry.traceContext());
        assertThat(retry.currentAttempt()).isEqualTo(2);
        assertThat(retry.attemptContext().evaluationAttempt()).isEqualTo(2);
        assertThat(body(retry).has("retry")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void recordedLegacyEvaluationOutputsAreRejectedAndRetriedWithoutChangingTheCandidate(int index)
            throws Exception {
        var sample = evaluationFailure(index);
        provider.evaluationOutputs.add(sample.path("content").asText());

        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        var first = provider.requests.get(2);
        var retry = provider.requests.get(3);
        assertThat(first.traceContext()).isEqualTo(retry.traceContext());
        assertThat(retry.currentAttempt()).isEqualTo(2);
        assertThat(retry.callBudget()).isNotNull();
        assertThat(body(first).has("retry")).isFalse();
        assertThat(body(retry).path("retry").path("error_code").asText())
                .isEqualTo(index == 2 || index == 5 ? "UNKNOWN_FAILURE_CODE" : "INVALID_STRUCTURE");
        assertThat(body(retry).path("retry").path("correction").asText()).isNotBlank();
        assertThat(EvaluationWireFixtures.analysisForRequest(body(retry)))
                .isEqualTo(EvaluationWireFixtures.analysisForRequest(body(first)));
        assertThat(body(retry).path("candidate_response"))
                .isEqualTo(body(first).path("candidate_response"));
        assertThat(retry.userPrompt())
                .doesNotContain("ValueInstantiationException", "StreamReadException");
    }

    @Test
    void malformedEvaluationStillStopsAtThreeAttemptsWithoutApprovingTheCandidate()
            throws Exception {
        var invalid = evaluationFailure(1).path("content").asText();
        provider.evaluationOutputs.addAll(List.of(invalid, invalid, invalid));

        var result =
                (RoleplayTurnResult.RecoveryRequired)
                        executor.execute(RoleplayFixtures.TURN_ID, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.SAFE_FALLBACK);
        assertThat(result.reason()).isEqualTo(RoleplayTurnResult.RecoveryReason.LIMIT_REACHED);
        assertThat(provider.requests).hasSize(5);
        assertThat(provider.requests.subList(3, 5))
                .allSatisfy(
                        request ->
                                assertThat(body(request).path("retry").path("error_code").asText())
                                        .isEqualTo("INVALID_STRUCTURE"));
    }

    @Test
    void correctedReanalysisRunsAnalysisAgainAndClearsEvaluationFeedbackForTheNewCandidate()
            throws Exception {
        provider.evaluationOutputs.add(evaluationFailure(1).path("content").asText());
        provider.decisions.add(EvaluationDecision.REANALYZE);

        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(body(provider.requests.get(3)).path("retry").path("error_code").asText())
                .isEqualTo("INVALID_STRUCTURE");
        assertThat(body(provider.requests.get(6)).has("retry")).isFalse();
        assertThat(provider.requests.get(6).traceContext().candidateId())
                .isNotEqualTo(provider.requests.get(2).traceContext().candidateId());
    }

    @Test
    void contradictoryEvaluationRetriesSameCandidateBeforeAcceptingAValidPass() throws Exception {
        provider.evaluationOutputs.add(contradictoryObservationEvaluation());
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        var first = provider.requests.get(2);
        var retry = provider.requests.get(3);
        assertThat(retry.traceContext()).isEqualTo(first.traceContext());
        assertThat(EvaluationWireFixtures.analysisForRequest(body(retry)))
                .isEqualTo(EvaluationWireFixtures.analysisForRequest(body(first)));
        assertThat(body(retry).path("candidate_response"))
                .isEqualTo(body(first).path("candidate_response"));
        assertThat(body(retry).path("retry").path("error_code").asString())
                .isEqualTo("INCONSISTENT_DECISION");
        assertThat(retry.callBudget()).isNotNull();
        assertThat(retry.userPrompt()).doesNotContain("goal review contradicts analysis check");
    }

    @Test
    void contradictoryEvaluationDoesNotTriggerReanalysisUntilAConsistentDecision()
            throws Exception {
        provider.evaluationOutputs.add(contradictoryObservationEvaluation());
        provider.decisions.add(EvaluationDecision.REANALYZE);
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(provider.requests.get(2).traceContext())
                .isEqualTo(provider.requests.get(3).traceContext());
        assertThat(provider.requests.get(4).traceContext().analysisId())
                .isNotEqualTo(provider.requests.get(2).traceContext().analysisId());
        assertThat(body(provider.requests.get(6)).has("retry")).isFalse();
    }

    @Test
    void threeContradictoryEvaluationsStopWithoutReanalysisOrDelivery() throws Exception {
        var invalid = contradictoryObservationEvaluation();
        provider.evaluationOutputs.addAll(List.of(invalid, invalid, invalid));
        var result =
                (RoleplayTurnResult.RecoveryRequired)
                        executor.execute(RoleplayFixtures.TURN_ID, steps);
        assertThat(result.operation()).isEqualTo(AiOperation.RESPONSE_EVALUATION);
        assertThat(result.target()).isEqualTo(RetryTarget.SAFE_FALLBACK);
        assertThat(result.reason()).isEqualTo(RoleplayTurnResult.RecoveryReason.LIMIT_REACHED);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(provider.requests.subList(2, 5))
                .allSatisfy(
                        request -> {
                            assertThat(request.traceContext())
                                    .isEqualTo(provider.requests.get(2).traceContext());
                            assertThat(request.currentAttempt()).isBetween(1, 3);
                        });
    }

    @Test
    void evaluationFeedbackNeverIncludesRejectedModelTextOrLeaksIntoAnotherTurn() {
        provider.evaluationOutputs.add("not JSON: 민감한 모델 원문");
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(provider.requests.get(3).userPrompt()).doesNotContain("민감한 모델 원문");

        var another =
                new StructuredRoleplayTurnSteps(
                        new RoleplayPromptFactory(mapper, null),
                        new StructuredLlmExecutor(provider),
                        trace,
                        input,
                        RoleplayObservationTestPlans.inputForScenario(input.scenario()));
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, another))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(body(provider.requests.get(6)).has("retry")).isFalse();
    }

    @Test
    void malformedGenerationRetriesSameCandidateWithoutFabricatedRevision() {
        provider.malformedOnce = AiOperation.RESPONSE_GENERATION;
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        var first = provider.requests.get(1);
        var retry = provider.requests.get(2);
        assertThat(first.traceContext().candidateId())
                .isEqualTo(retry.traceContext().candidateId());
        var retriedBody = (tools.jackson.databind.node.ObjectNode) body(retry).deepCopy();
        assertThat(retriedBody.path("output_retry").path("error_code").asString())
                .isEqualTo("INVALID_STRUCTURE");
        retriedBody.remove("output_retry");
        assertThat(retriedBody).isEqualTo(body(first));
        assertThat(body(retry).has("retry")).isFalse();
        assertThat(body(retry).get("previous_failed_candidate_ids").size()).isZero();
    }

    @Test
    void generationOutputFeedbackDoesNotLeakIntoANewCandidate() {
        provider.malformedOnce = AiOperation.RESPONSE_GENERATION;
        provider.decisions.add(EvaluationDecision.REGENERATE);
        provider.decisions.add(EvaluationDecision.PASS);
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        var generations =
                provider.requests.stream()
                        .filter(r -> r.operation() == AiOperation.RESPONSE_GENERATION)
                        .toList();
        assertThat(generations).hasSize(3);
        assertThat(body(generations.get(1)).has("output_retry")).isTrue();
        assertThat(body(generations.get(2)).has("output_retry")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = EvaluationDecision.class,
            names = {"REGENERATE", "SAFETY_REGENERATE"})
    void modelRevisionPassesActualFailedCandidateAndInstructions(EvaluationDecision decision) {
        provider.decisions.add(decision);
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        var first = provider.requests.get(1);
        var retry = provider.requests.get(3);
        assertThat(retry.traceContext().analysisId()).isEqualTo(first.traceContext().analysisId());
        assertThat(retry.traceContext().candidateId())
                .isNotEqualTo(first.traceContext().candidateId());
        assertThat(body(retry).get("previous_failed_candidate_ids").get(0).asText())
                .isEqualTo(first.traceContext().candidateId().toString());
        assertThat(body(retry).get("retry").get("failed_candidates").get(0).get("text").asText())
                .isEqualTo("친구는 어떤 기분일까?");
        assertThat(
                        body(retry)
                                .get("retry")
                                .get("revision_instruction")
                                .get("required")
                                .get(0)
                                .asText())
                .isEqualTo("ask one question");
    }

    @Test
    void laterRevisionRetainsBothEarlierFailedCandidates() {
        provider.decisions.addAll(
                List.of(EvaluationDecision.REGENERATE, EvaluationDecision.REGENERATE));
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        var thirdGeneration = provider.requests.get(5);
        assertThat(body(thirdGeneration).get("retry").get("failed_candidates").size()).isEqualTo(2);
        assertThat(body(thirdGeneration).get("previous_failed_candidate_ids").size()).isEqualTo(2);
    }

    @Test
    void reanalysisCreatesNewAnalysisTraceAndMapsFocusWithoutInventingGenerationRetry() {
        provider.decisions.add(EvaluationDecision.REANALYZE);
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        var reanalysis = provider.requests.get(3);
        assertThat(reanalysis.traceContext().analysisId())
                .isNotEqualTo(provider.requests.getFirst().traceContext().analysisId());
        assertThat(body(reanalysis).get("retry").get("failure_codes").get(0).asText())
                .isEqualTo("UNSUPPORTED_CAUSE_ANALYSIS");
        assertThat(body(reanalysis).get("retry").get("focus").get(0).asText()).isEqualTo("shorten");
        assertThat(body(reanalysis).get("retry").get("do_not_assume").get(0).asText())
                .isEqualTo("invented facts");
        assertThat(body(reanalysis).path("retry").path("failed_analysis"))
                .isEqualTo(body(provider.requests.get(1)).path("analysis_result"));
        assertThat(
                        body(reanalysis)
                                .path("retry")
                                .path("failed_analysis")
                                .path("goal_observation")
                                .path("analysis_id")
                                .asText())
                .isEqualTo(provider.requests.getFirst().traceContext().analysisId().toString());
        assertThat(body(provider.requests.get(4)).has("retry")).isFalse();
    }

    @Test
    void repeatedReanalysisReceivesTheLatestRejectedAnalysis() {
        provider.decisions.addAll(
                List.of(EvaluationDecision.REANALYZE, EvaluationDecision.REANALYZE));
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);

        var latest = body(provider.requests.get(6)).path("retry").path("failed_analysis");
        assertThat(latest).isEqualTo(body(provider.requests.get(4)).path("analysis_result"));
        assertThat(latest.path("goal_observation").path("analysis_id").asText())
                .isNotEqualTo(provider.requests.getFirst().traceContext().analysisId().toString());
    }

    @Test
    void strictStrategyMismatchNeverReachesEvaluation() {
        provider.wrongStrategy = true;
        var result =
                (RoleplayTurnResult.RecoveryRequired)
                        executor.execute(RoleplayFixtures.TURN_ID, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.SAFE_FALLBACK);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_GENERATION);
    }

    @Test
    void malformedAnalysisStopsAfterThreeCallsWithoutDownstreamWork() {
        provider.alwaysMalformed = AiOperation.CAUSE_ANALYSIS;
        var result =
                (RoleplayTurnResult.RecoveryRequired)
                        executor.execute(RoleplayFixtures.TURN_ID, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.INPUT_CONFIRMATION);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.CAUSE_ANALYSIS);
        for (var request : provider.requests) {
            assertThat(body(request).path("retry").has("failed_analysis")).isFalse();
            assertThat(request.userPrompt()).doesNotContain("not JSON");
        }
    }

    @Test
    void modelReservedDecisionBecomesParserFailureAndSameCandidateReevaluation() {
        provider.defaultDecision = EvaluationDecision.SAFE_FALLBACK;
        var result =
                (RoleplayTurnResult.RecoveryRequired)
                        executor.execute(RoleplayFixtures.TURN_ID, steps);
        assertThat(result.operation()).isEqualTo(AiOperation.RESPONSE_EVALUATION);
        assertThat(operations())
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(
                        provider.requests.subList(2, 5).stream()
                                .map(r -> r.traceContext().candidateId())
                                .distinct())
                .hasSize(1);
    }

    @Test
    void anotherTurnIsRejectedBeforeProviderInvocation() {
        assertThatThrownBy(() -> executor.execute(UUID.randomUUID(), steps))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.requests).isEmpty();
        assertThatThrownBy(
                        () ->
                                new StructuredRoleplayTurnSteps(
                                        new RoleplayPromptFactory(mapper, null),
                                        new StructuredLlmExecutor(provider),
                                        RoleplayFixtures.trace(null, null),
                                        new RoleplayTurnInput(
                                                input.scenario(),
                                                input.state(),
                                                new RoleplayTurnInput.LearnerTurn(
                                                        UUID.randomUUID(), "test"),
                                                input.recentDialogue()),
                                        RoleplayObservationTestPlans.inputForScenario(
                                                (new RoleplayTurnInput(
                                                                input.scenario(),
                                                                input.state(),
                                                                new RoleplayTurnInput.LearnerTurn(
                                                                        UUID.randomUUID(), "test"),
                                                                input.recentDialogue()))
                                                        .scenario())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String contradictoryObservationEvaluation() {
        var body =
                (tools.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                RoleplayFixtures.evaluationOutputJson(
                                        "REANALYZE", "[\"UNSUPPORTED_CAUSE_ANALYSIS\"]", "null"));
        body.put("candidate_id", "${candidate_id}");
        body.put("analysis_id", "${analysis_id}");
        body.put("decision", "PASS");
        ((tools.jackson.databind.node.ObjectNode) body.path("observation_reviews").path("o0"))
                .put("verdict", "UNSUPPORTED")
                .put("reason_code", "STATUS_MISMATCH")
                .put("evidence_quote", input.learnerTurn().canonicalUtterance());
        return body.toString();
    }

    @Test
    void indeterminateReviewRetriesTheSameCandidateAndStopsAtTheEvaluationLimit() {
        var body =
                (tools.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                RoleplayFixtures.evaluationOutputJson("PASS", "[]", "null"));
        body.put("candidate_id", "${candidate_id}");
        body.put("analysis_id", "${analysis_id}");
        ((tools.jackson.databind.node.ObjectNode) body.path("observation_reviews").path("o0"))
                .put("verdict", "INSUFFICIENT_CONTEXT")
                .put("reason_code", "INSUFFICIENT_CONTEXT")
                .put("explanation", "문맥이 부족하다.");
        provider.evaluationOutputs.addAll(
                List.of(body.toString(), body.toString(), body.toString()));
        var result =
                (RoleplayTurnResult.RecoveryRequired)
                        executor.execute(RoleplayFixtures.TURN_ID, steps);
        assertThat(result.operation()).isEqualTo(AiOperation.RESPONSE_EVALUATION);
        assertThat(result.target()).isEqualTo(RetryTarget.SAFE_FALLBACK);
        assertThat(result.reason()).isEqualTo(RoleplayTurnResult.RecoveryReason.LIMIT_REACHED);
        assertThat(provider.requests).hasSize(5);
        assertThat(provider.requests.subList(2, 5))
                .allSatisfy(
                        request -> {
                            assertThat(request.traceContext())
                                    .isEqualTo(provider.requests.get(2).traceContext());
                            assertThat(request.callBudget()).isNotNull();
                        });
        assertThat(body(provider.requests.get(3)).path("retry").path("error_code").asString())
                .isEqualTo("OBSERVATION_CONTEXT_INSUFFICIENT");
    }

    private List<AiOperation> operations() {
        return provider.requests.stream().map(LlmRequest::operation).toList();
    }

    private JsonNode body(LlmRequest request) {
        return mapper.readTree(request.userPrompt());
    }

    private JsonNode evaluationFailure(int index) throws Exception {
        try (var stream =
                getClass()
                        .getResourceAsStream("/ai/mock/roleplay-evaluation-output-failures.json")) {
            return mapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .get(index);
        }
    }

    @Test
    void unknownFailureCodeIsCorrectedUsingTheServerAllowedList() {
        provider.evaluationOutputs.add(
                RoleplayFixtures.evaluationOutputJson(
                        "REANALYZE", "[\"ANALYSIS_SUPPORTED\"]", "null"));
        assertThat(executor.execute(RoleplayFixtures.TURN_ID, steps))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(body(provider.requests.get(3)).path("retry").path("error_code").asText())
                .isEqualTo("UNKNOWN_FAILURE_CODE");
        var allowed = body(provider.requests.get(3)).path("retry").path("allowed_failure_codes");
        assertThat(allowed).hasSize(EvaluationOutputSchema.modelFailureCodes().size());
        assertThat(allowed.toString())
                .doesNotContain(
                        "UNSUPPORTED_CAUSE_ANALYSIS",
                        "STATE_STRATEGY_MISMATCH",
                        "ANALYSIS_SUPPORTED");
    }

    private final class ScriptedProvider implements LlmProvider {
        private final List<LlmRequest> requests = new ArrayList<>();
        private final Deque<EvaluationDecision> decisions = new ArrayDeque<>();
        private final Deque<String> evaluationOutputs = new ArrayDeque<>();
        private EvaluationDecision defaultDecision = EvaluationDecision.PASS;
        private AiOperation timeoutOnce;
        private AiOperation malformedOnce;
        private AiOperation alwaysMalformed;
        private boolean wrongStrategy;
        private int generations;

        @Override
        public AiCallResult<LlmCompletion> complete(LlmRequest request) {
            requests.add(request);
            var metadata =
                    new AiCallMetadata(
                            request.traceContext().requestId(),
                            request.operation(),
                            "fake",
                            "fake-model",
                            request.promptVersion(),
                            request.responseSchemaVersion(),
                            request.policyVersion(),
                            0,
                            request.currentAttempt(),
                            null,
                            null,
                            "stop");
            if (request.operation() == timeoutOnce) {
                timeoutOnce = null;
                return new AiCallResult.Failure<>(
                        new AiFailure(AiFailureType.TIMEOUT, null), metadata);
            }
            String content;
            if (request.operation() == malformedOnce || request.operation() == alwaysMalformed) {
                malformedOnce = null;
                content = "not JSON";
            } else {
                content =
                        switch (request.operation()) {
                            case CAUSE_ANALYSIS ->
                                    RoleplayFixtures.analysisJson(
                                            "PROBE_EMOTION", RoleplayFixtures.MG_EMOTION, "S1");
                            case RESPONSE_GENERATION -> generation(request);
                            case RESPONSE_EVALUATION -> evaluation(request);
                            default -> throw new AssertionError("Unexpected operation");
                        };
            }
            return new AiCallResult.Success<>(
                    new LlmCompletion(
                            RoleplayFixtures.bindAnalysis(
                                    content, request.traceContext().analysisId()),
                            "fake",
                            "fake-model",
                            "stop",
                            null,
                            null),
                    metadata);
        }

        private String generation(LlmRequest request) {
            generations++;
            var message = body(request);
            var response = new LinkedHashMap<String, Object>();
            response.put("candidate_id", request.traceContext().candidateId());
            response.put("turn_id", request.traceContext().turnId());
            response.put(
                    "text",
                    generations == 1
                            ? "친구는 어떤 기분일까?"
                            : generations == 2 ? "넘어진 친구의 마음은 어떨까?" : "울고 있는 친구는 어떤 마음일까?");
            response.put("response_type", "GUIDING_QUESTION");
            response.put("strategy_used", wrongStrategy ? "PROBE_EVENT" : "PROBE_EMOTION");
            response.put("target_micro_goal_id", RoleplayFixtures.MG_EMOTION);
            response.put("support_level", "S1");
            response.put(
                    "previous_failed_candidate_ids", message.get("previous_failed_candidate_ids"));
            return mapper.writeValueAsString(response);
        }

        private String evaluation(LlmRequest request) {
            if (!evaluationOutputs.isEmpty()) {
                return evaluationOutputs
                        .removeFirst()
                        .replace("${candidate_id}", request.traceContext().candidateId().toString())
                        .replace("${analysis_id}", request.traceContext().analysisId().toString());
            }
            var decision = decisions.isEmpty() ? defaultDecision : decisions.removeFirst();
            var response = new LinkedHashMap<String, Object>();
            response.put(
                    "goal_observation_review",
                    RoleplayFixtures.review(request.traceContext().analysisId()));
            response.put("candidate_id", request.traceContext().candidateId());
            response.put("decision", decision.name());
            List<String> failureCodes =
                    decision == EvaluationDecision.PASS
                            ? List.of()
                            : decision == EvaluationDecision.REANALYZE
                                    ? List.of("UNSUPPORTED_CAUSE_ANALYSIS")
                                    : List.of("TOO_LONG");
            response.put("checks", RoleplayFixtures.evaluationChecks(failureCodes));
            response.put("failure_codes", failureCodes);
            response.put(
                    "revision_instruction",
                    Map.of(
                            "keep",
                            List.of("emotion focus"),
                            "change",
                            List.of("shorten"),
                            "avoid",
                            List.of("invented facts"),
                            "required",
                            List.of("ask one question")));
            return EvaluationWireFixtures.forRequest(
                    mapper.writeValueAsString(response), body(request));
        }
    }
}
