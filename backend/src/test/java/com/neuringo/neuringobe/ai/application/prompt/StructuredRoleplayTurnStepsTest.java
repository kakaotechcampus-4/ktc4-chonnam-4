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
                    input);
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
        assertThat(first.userPrompt()).isEqualTo(retry.userPrompt());
        assertThat(body(retry).has("retry")).isFalse();
        assertThat(body(retry).get("previous_failed_candidate_ids").size()).isZero();
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
                .isEqualTo("TOO_LONG");
        assertThat(body(reanalysis).get("retry").get("focus").get(0).asText()).isEqualTo("shorten");
        assertThat(body(reanalysis).get("retry").get("do_not_assume").get(0).asText())
                .isEqualTo("invented facts");
        assertThat(body(provider.requests.get(4)).has("retry")).isFalse();
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
                                                input.recentDialogue())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<AiOperation> operations() {
        return provider.requests.stream().map(LlmRequest::operation).toList();
    }

    private JsonNode body(LlmRequest request) {
        return mapper.readTree(request.userPrompt());
    }

    private final class ScriptedProvider implements LlmProvider {
        private final List<LlmRequest> requests = new ArrayList<>();
        private final Deque<EvaluationDecision> decisions = new ArrayDeque<>();
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
                    new LlmCompletion(content, "fake", "fake-model", "stop", null, null), metadata);
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
            var decision = decisions.isEmpty() ? defaultDecision : decisions.removeFirst();
            var target =
                    switch (decision) {
                        case REGENERATE, SAFETY_REGENERATE -> "RESPONSE_GENERATION";
                        case REANALYZE -> "CAUSE_ANALYSIS";
                        case SAFE_FALLBACK -> "SAFE_FALLBACK";
                        default -> null;
                    };
            var response = new LinkedHashMap<String, Object>();
            response.put("candidate_id", request.traceContext().candidateId());
            response.put("decision", decision.name());
            response.put("safe_to_send", decision == EvaluationDecision.PASS);
            response.put("retry_target", target);
            response.put("critical_failure_count", 0);
            response.put(
                    "failure_codes",
                    decision == EvaluationDecision.PASS ? List.of() : List.of("TOO_LONG"));
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
            return mapper.writeValueAsString(response);
        }
    }
}
