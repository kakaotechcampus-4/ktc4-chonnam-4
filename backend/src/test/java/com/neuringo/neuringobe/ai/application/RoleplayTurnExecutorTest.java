package com.neuringo.neuringobe.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.structured.JacksonLlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplayTurnExecutorTest {
    private static final UUID TURN = UUID.randomUUID();
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID GOAL = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID ACTIVITY = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID ANALYSIS = UUID.randomUUID();
    private static final List<AiOperation> ORDER =
            List.of(
                    AiOperation.CAUSE_ANALYSIS,
                    AiOperation.RESPONSE_GENERATION,
                    AiOperation.RESPONSE_EVALUATION);

    private final RecordingSteps steps = new RecordingSteps();
    private final RoleplayTurnExecutor executor = new RoleplayTurnExecutor(() -> CANDIDATE);

    @Test
    void runsThreeCallsInOrderAndReturnsOnlyApprovedCandidate() {
        var result = (RoleplayTurnResult.Ready) executor.execute(TURN, steps);

        assertThat(steps.calls).containsExactlyElementsOf(ORDER);
        assertThat(steps.generatedFrom).isSameAs(result.analysis());
        assertThat(steps.evaluatedAnalysis).isSameAs(result.analysis());
        assertThat(steps.evaluatedCandidate).isSameAs(result.candidate());
        assertThat(steps.issuedCandidateId).isEqualTo(CANDIDATE);
        assertThat(result.candidate().text()).isEqualTo("친구는 어떤 기분일까?");
        assertThat(result.evaluation().canDeliver()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = AiOperation.class,
            names = {"CAUSE_ANALYSIS", "RESPONSE_GENERATION", "RESPONSE_EVALUATION"})
    void stopsAtProviderFailureAndPreservesItsMetadata(AiOperation operation) {
        steps.failedOperation = operation;
        var result = (RoleplayTurnResult.CallFailed) executor.execute(TURN, steps);

        assertThat(result.operation()).isEqualTo(operation);
        assertThat(result.failure().type()).isEqualTo(AiFailureType.TIMEOUT);
        assertThat(result.metadata().requestId()).isEqualTo(REQUEST);
        assertThat(steps.calls).containsExactlyElementsOf(through(operation));
    }

    @ParameterizedTest
    @EnumSource(
            value = AiOperation.class,
            names = {"CAUSE_ANALYSIS", "RESPONSE_GENERATION", "RESPONSE_EVALUATION"})
    void stopsAtMalformedOutputWithoutExecutingLaterSteps(AiOperation operation) {
        steps.outputs.put(operation, "not JSON");
        var result = (RoleplayTurnResult.CallFailed) executor.execute(TURN, steps);

        assertThat(result.operation()).isEqualTo(operation);
        assertThat(result.failure().type()).isEqualTo(AiFailureType.INVALID_OUTPUT_FORMAT);
        assertThat(steps.calls).containsExactlyElementsOf(through(operation));
    }

    @ParameterizedTest
    @CsvSource({"analysis,turn", "generation,turn", "generation,candidate", "evaluation,candidate"})
    void blocksOutputFromAnotherTurnOrCandidate(String stage, String identifier) {
        AiOperation operation =
                switch (stage) {
                    case "analysis" -> AiOperation.CAUSE_ANALYSIS;
                    case "generation" -> AiOperation.RESPONSE_GENERATION;
                    default -> AiOperation.RESPONSE_EVALUATION;
                };
        UUID expected = identifier.equals("turn") ? TURN : CANDIDATE;
        steps.outputs.compute(
                operation,
                (ignored, json) -> json.replace(expected.toString(), UUID.randomUUID().toString()));
        var result = (RoleplayTurnResult.CallFailed) executor.execute(TURN, steps);

        assertThat(result.operation()).isEqualTo(operation);
        assertThat(result.failure().type()).isEqualTo(AiFailureType.INVALID_OUTPUT_FORMAT);
        assertThat(steps.calls).containsExactlyElementsOf(through(operation));
    }

    @ParameterizedTest
    @CsvSource({"false,0", "true,1", "false,1"})
    void rejectsPassWhenAnySafetyConditionIsMissing(boolean safe, int critical) {
        steps.outputs.put(
                AiOperation.RESPONSE_EVALUATION, evaluation(safe, critical, "PASS", "null"));
        var result = (RoleplayTurnResult.Rejected) executor.execute(TURN, steps);

        assertThat(result.evaluation().canDeliver()).isFalse();
        assertThat(steps.calls).containsExactlyElementsOf(ORDER);
    }

    @ParameterizedTest
    @CsvSource({"REGENERATE,RESPONSE_GENERATION", "REANALYZE,CAUSE_ANALYSIS"})
    void retainsRejectionDecisionWithoutReturningFailedCandidateText(
            String decision, String target) {
        steps.outputs.put(
                AiOperation.RESPONSE_EVALUATION,
                evaluation(false, 0, decision, "\"" + target + "\""));
        var result = (RoleplayTurnResult.Rejected) executor.execute(TURN, steps);

        assertThat(result.evaluation().decision().name()).isEqualTo(decision);
        assertThat(result.evaluation().retryTarget().name()).isEqualTo(target);
        assertThat(result.toString()).doesNotContain("친구는 어떤 기분일까?");
        assertThat(steps.calls).containsExactlyElementsOf(ORDER);
    }

    @Test
    void doesNotHideProgrammingDefectsAsSafeResponses() {
        steps.defect = new IllegalStateException("synthetic defect");
        assertThatThrownBy(() -> executor.execute(TURN, steps)).isSameAs(steps.defect);
        assertThat(steps.calls).containsExactly(AiOperation.CAUSE_ANALYSIS);
    }

    private static List<AiOperation> through(AiOperation operation) {
        return ORDER.subList(0, ORDER.indexOf(operation) + 1);
    }

    private static String evaluation(boolean safe, int critical, String decision, String target) {
        return """
                {"candidate_id":"%s","safe_to_send":%s,"decision":"%s",
                 "critical_failure_count":%d,"retry_target":%s,"failure_codes":[],
                 "revision_instruction":{"keep":[],"change":[],"avoid":[],"required":[]}}
                """
                .formatted(CANDIDATE, safe, decision, critical, target);
    }

    /** Synthetic prompt bindings; real RoleplayPromptFactory integration remains a dependency. */
    private static final class RecordingSteps implements RoleplayTurnSteps {
        private final List<AiOperation> calls = new ArrayList<>();
        private final EnumMap<AiOperation, String> outputs = new EnumMap<>(AiOperation.class);
        private AiOperation failedOperation;
        private RuntimeException defect;
        private AnalysisResult generatedFrom;
        private AnalysisResult evaluatedAnalysis;
        private CandidateResponse evaluatedCandidate;
        private UUID issuedCandidateId;
        private final StructuredLlmExecutor llm =
                new StructuredLlmExecutor(
                        request -> {
                            AiOperation operation = request.operation();
                            calls.add(operation);
                            if (defect != null) throw defect;
                            AiCallMetadata metadata =
                                    new AiCallMetadata(
                                            REQUEST,
                                            operation,
                                            "fake",
                                            "fake-model",
                                            "synthetic/v1",
                                            "v1",
                                            null,
                                            1,
                                            1,
                                            null,
                                            null,
                                            "stop");
                            if (operation == failedOperation) {
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null), metadata);
                            }
                            return new AiCallResult.Success<>(
                                    new LlmCompletion(
                                            outputs.get(operation),
                                            "fake",
                                            "fake-model",
                                            "stop",
                                            null,
                                            null),
                                    metadata);
                        });

        private RecordingSteps() {
            outputs.put(
                    AiOperation.CAUSE_ANALYSIS,
                    """
                    {"turn_id":"%s","learning_state":"PARTIAL_UNDERSTANDING",
                     "primary_gap":{"code":"MISSING_EMOTION"},
                     "next_strategy":{"type":"ASK_EMOTION","target_micro_goal_id":"%s"},
                     "analysis_confidence":0.9}
                    """
                            .formatted(TURN, GOAL));
            outputs.put(
                    AiOperation.RESPONSE_GENERATION,
                    """
                    {"candidate_id":"%s","turn_id":"%s","text":"친구는 어떤 기분일까?",
                     "response_type":"QUESTION","strategy_used":"ASK_EMOTION",
                     "target_micro_goal_id":"%s","support_level":"S1"}
                    """
                            .formatted(CANDIDATE, TURN, GOAL));
            outputs.put(AiOperation.RESPONSE_EVALUATION, evaluation(true, 0, "PASS", "null"));
        }

        @Override
        public AiCallResult<AnalysisResult> analyze(UUID turnId) {
            return call(turnId, null, AiOperation.CAUSE_ANALYSIS, AnalysisResult.class);
        }

        @Override
        public AiCallResult<CandidateResponse> generate(
                UUID turnId, UUID candidateId, AnalysisResult analysis) {
            generatedFrom = analysis;
            issuedCandidateId = candidateId;
            return call(
                    turnId, candidateId, AiOperation.RESPONSE_GENERATION, CandidateResponse.class);
        }

        @Override
        public AiCallResult<EvaluationResult> evaluate(
                UUID turnId, AnalysisResult analysis, CandidateResponse candidate) {
            evaluatedAnalysis = analysis;
            evaluatedCandidate = candidate;
            return call(
                    turnId,
                    candidate.candidateId(),
                    AiOperation.RESPONSE_EVALUATION,
                    EvaluationResult.class);
        }

        private <T> AiCallResult<T> call(
                UUID turnId, UUID candidateId, AiOperation operation, Class<T> type) {
            var trace =
                    new AiTraceContext(
                            REQUEST,
                            ACTIVITY,
                            null,
                            null,
                            null,
                            null,
                            SESSION,
                            turnId,
                            ANALYSIS,
                            candidateId);
            var request =
                    new LlmRequest(
                            trace,
                            new AiAttemptContext(1, 1, 1),
                            operation,
                            1,
                            "synthetic system",
                            "synthetic learner input",
                            "synthetic/v1",
                            "v1",
                            null);
            return llm.execute(
                    request, new JacksonLlmOutputParser<>(JsonMapper.builder().build(), type));
        }
    }
}
