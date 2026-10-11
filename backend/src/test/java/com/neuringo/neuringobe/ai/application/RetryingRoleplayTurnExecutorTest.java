package com.neuringo.neuringobe.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.roleplay.RetryableRoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.roleplay.RetryingRoleplayTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRetryContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.RecoveryReason;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RetryingRoleplayTurnExecutorTest {
    private static final UUID TURN = UUID.randomUUID();
    private static final UUID GOAL = UUID.randomUUID();
    private final ScriptedSteps steps = new ScriptedSteps();
    private final RetryingRoleplayTurnExecutor executor = new RetryingRoleplayTurnExecutor();

    @Test
    void normalTurnCallsEveryStageOnce() {
        assertThat(executor.execute(TURN, steps)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(steps.calls).containsExactly("A", "G", "E");
        assertThat(steps.contexts.get(2).attempts().analysisAttempt()).isEqualTo(1);
        assertThat(steps.contexts.get(2).attempts().generationAttempt()).isEqualTo(1);
        assertThat(steps.contexts.get(2).attempts().evaluationAttempt()).isEqualTo(1);
    }

    @Test
    void evaluationFailureReevaluatesTheSameCandidateWithoutNewAnalysisOrGeneration() {
        steps.failAt = AiOperation.RESPONSE_EVALUATION;
        steps.failuresRemaining = 1;
        assertThat(executor.execute(TURN, steps)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(steps.calls).containsExactly("A", "G", "E", "E");
        assertThat(steps.evaluatedCandidates.get(0)).isSameAs(steps.evaluatedCandidates.get(1));
        assertThat(steps.contexts.get(3).stageAttempt()).isEqualTo(2);
    }

    @Test
    void generationTechnicalRetryKeepsIdAndDoesNotInventRejectedCandidate() {
        steps.failAt = AiOperation.RESPONSE_GENERATION;
        steps.failuresRemaining = 1;
        assertThat(executor.execute(TURN, steps)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(steps.calls).containsExactly("A", "G", "G", "E");
        assertThat(steps.generatedIds.get(0)).isEqualTo(steps.generatedIds.get(1));
        assertThat(steps.contexts.get(2).rejectedCandidates()).isEmpty();
    }

    @Test
    void analysisTechnicalRetryDoesNotRunDownstreamEarly() {
        steps.failAt = AiOperation.CAUSE_ANALYSIS;
        steps.failuresRemaining = 1;
        assertThat(executor.execute(TURN, steps)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(steps.calls).containsExactly("A", "A", "G", "E");
    }

    @ParameterizedTest
    @EnumSource(
            value = AiOperation.class,
            names = {"CAUSE_ANALYSIS", "RESPONSE_GENERATION", "RESPONSE_EVALUATION"})
    void technicalRetriesStopAfterThreeCallsToTheFailingStage(AiOperation operation) {
        steps.failAt = operation;
        steps.failuresRemaining = 100;
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.operation()).isEqualTo(operation);
        assertThat(result.reason()).isEqualTo(RecoveryReason.LIMIT_REACHED);
        assertThat(result.target())
                .isEqualTo(
                        operation == AiOperation.CAUSE_ANALYSIS
                                ? RetryTarget.INPUT_CONFIRMATION
                                : RetryTarget.SAFE_FALLBACK);
        assertThat(steps.calls.stream().filter(s -> s.equals(stage(operation))).count())
                .isEqualTo(3);
        assertThat(steps.calls.getLast()).isEqualTo(stage(operation));
        assertThat(steps.contexts.getLast().stageAttempt()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(
            value = AiOperation.class,
            names = {"CAUSE_ANALYSIS", "RESPONSE_GENERATION", "RESPONSE_EVALUATION"})
    void nonRetryableFailureStopsImmediately(AiOperation operation) {
        steps.failAt = operation;
        steps.failureType = AiFailureType.AUTHENTICATION_ERROR;
        steps.failuresRemaining = 100;
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.reason()).isEqualTo(RecoveryReason.NON_RETRYABLE_FAILURE);
        assertThat(steps.calls.stream().filter(s -> s.equals(stage(operation))).count())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = EvaluationDecision.class,
            names = {"REGENERATE", "SAFETY_REGENERATE"})
    void regenerationKeepsAnalysisAndPassesRevisionWithANewCandidateId(
            EvaluationDecision decision) {
        steps.decisions.add(decision);
        var result = (RoleplayTurnResult.Ready) executor.execute(TURN, steps);
        assertThat(steps.calls).containsExactly("A", "G", "E", "G", "E");
        assertThat(steps.generatedAnalyses.get(0)).isSameAs(steps.generatedAnalyses.get(1));
        assertThat(steps.generatedIds.get(0)).isNotEqualTo(steps.generatedIds.get(1));
        RoleplayRetryContext retry = steps.contexts.get(3);
        assertThat(retry.rejectedCandidates()).hasSize(1);
        var rejected = retry.rejectedCandidates().getFirst();
        assertThat(rejected.candidate()).isSameAs(steps.evaluatedCandidates.getFirst());
        assertThat(rejected.evaluation().revisionInstruction().required())
                .containsExactly("ask one question");
        assertThat(retry.attempts().generationAttempt()).isEqualTo(2);
        assertThat(retry.stageAttempt()).isEqualTo(1);
        assertThat(steps.contexts.getFirst().rejectedCandidates()).isEmpty();
        assertThat(result.candidate().candidateId()).isEqualTo(steps.generatedIds.getLast());
    }

    @Test
    void reanalysisDiscardsOldAnalysisAndCandidateButKeepsHistoryAndCounts() {
        steps.decisions.add(EvaluationDecision.REANALYZE);
        assertThat(executor.execute(TURN, steps)).isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(steps.calls).containsExactly("A", "G", "E", "A", "G", "E");
        assertThat(steps.generatedAnalyses.get(0)).isNotSameAs(steps.generatedAnalyses.get(1));
        assertThat(steps.contexts.get(3).attempts().analysisAttempt()).isEqualTo(2);
        assertThat(steps.contexts.get(3).rejectedCandidates()).hasSize(1);
        assertThat(steps.generatedIds).doesNotHaveDuplicates();
    }

    @Test
    void technicalAnalysisRetriesAndModelReanalysisShareTheThreeExecutionLimit() {
        steps.failAt = AiOperation.CAUSE_ANALYSIS;
        steps.failuresRemaining = 2;
        steps.decisions.add(EvaluationDecision.REANALYZE);
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.INPUT_CONFIRMATION);
        assertThat(result.reason()).isEqualTo(RecoveryReason.LIMIT_REACHED);
        assertThat(steps.calls).containsExactly("A", "A", "A", "G", "E");
    }

    @ParameterizedTest
    @EnumSource(
            value = AiOperation.class,
            names = {"RESPONSE_GENERATION", "RESPONSE_EVALUATION"})
    void newCandidateHasItsOwnTechnicalBudgetWhileTotalCountsKeepIncreasing(AiOperation operation) {
        steps.failAt = operation;
        steps.failuresRemaining = 2;
        steps.decisions.add(EvaluationDecision.REGENERATE);
        assertThat(executor.execute(TURN, steps)).isInstanceOf(RoleplayTurnResult.Ready.class);
        if (operation == AiOperation.RESPONSE_GENERATION) {
            assertThat(steps.calls).containsExactly("A", "G", "G", "G", "E", "G", "E");
            assertThat(steps.contexts.get(5).attempts().generationAttempt()).isEqualTo(4);
            assertThat(steps.contexts.get(5).stageAttempt()).isEqualTo(1);
        } else {
            assertThat(steps.calls).containsExactly("A", "G", "E", "E", "E", "G", "E");
            assertThat(steps.contexts.getLast().attempts().evaluationAttempt()).isEqualTo(4);
            assertThat(steps.contexts.getLast().stageAttempt()).isEqualTo(1);
        }
        assertThat(steps.contexts.getLast().rejectedCandidates()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"REGENERATE,SAFE_FALLBACK,1", "REANALYZE,INPUT_CONFIRMATION,3"})
    void repeatedModelFailureCannotResetBudgets(String decision, String target, int analysisCalls) {
        steps.defaultDecision = EvaluationDecision.valueOf(decision);
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.valueOf(target));
        assertThat(result.reason()).isEqualTo(RecoveryReason.LIMIT_REACHED);
        assertThat(steps.generatedIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(steps.calls.stream().filter("A"::equals).count()).isEqualTo(analysisCalls);
    }

    @Test
    void alternatingAnalysisAndGenerationFailureCannotCreateAFourthCandidate() {
        steps.decisions.addAll(
                List.of(
                        EvaluationDecision.REANALYZE,
                        EvaluationDecision.REGENERATE,
                        EvaluationDecision.REANALYZE));
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.SAFE_FALLBACK);
        assertThat(steps.calls).containsExactly("A", "G", "E", "A", "G", "E", "G", "E");
    }

    @ParameterizedTest
    @CsvSource({"CONFIRM_INPUT,INPUT_CONFIRMATION", "SAFETY_ESCALATION,SAFETY_ESCALATION"})
    void inputAndSafetyRequestsExitWithoutAnotherLlmCall(String decision, String target) {
        steps.defaultDecision = EvaluationDecision.valueOf(decision);
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.valueOf(target));
        assertThat(result.reason()).isEqualTo(RecoveryReason.MODEL_REQUESTED);
        assertThat(steps.calls).containsExactly("A", "G", "E");
    }

    @ParameterizedTest
    @EnumSource(
            value = EvaluationDecision.class,
            names = {"RETRY_EVALUATION", "SAFE_FALLBACK"})
    void modelCannotChooseServerReservedRecoveryDecisions(EvaluationDecision decision) {
        steps.defaultDecision = decision;
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.reason()).isEqualTo(RecoveryReason.INCONSISTENT_EVALUATION);
        assertThat(steps.calls).containsExactly("A", "G", "E");
    }

    @Test
    void inconsistentDecisionAndTargetCannotRedirectTheExecution() {
        steps.defaultDecision = EvaluationDecision.REGENERATE;
        steps.overrideTarget = RetryTarget.CAUSE_ANALYSIS;
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.reason()).isEqualTo(RecoveryReason.INCONSISTENT_EVALUATION);
        assertThat(steps.calls).containsExactly("A", "G", "E");
    }

    @Test
    void invalidCandidateIdsUseTechnicalRetriesWithoutFakeRevisionHistory() {
        steps.wrongGeneratedId = true;
        var result = (RoleplayTurnResult.RecoveryRequired) executor.execute(TURN, steps);
        assertThat(result.target()).isEqualTo(RetryTarget.SAFE_FALLBACK);
        assertThat(steps.calls).containsExactly("A", "G", "G", "G");
        assertThat(steps.generatedIds.stream().distinct().count()).isEqualTo(1);
        assertThat(steps.contexts.getLast().rejectedCandidates()).isEmpty();
    }

    @Test
    void separateExecutionsDoNotShareCountersOrRejectedCandidates() {
        steps.decisions.add(EvaluationDecision.REGENERATE);
        executor.execute(TURN, steps);
        ScriptedSteps next = new ScriptedSteps();
        assertThat(executor.execute(UUID.randomUUID(), next))
                .isInstanceOf(RoleplayTurnResult.Ready.class);
        assertThat(next.calls).containsExactly("A", "G", "E");
        assertThat(next.contexts.getFirst().stageAttempt()).isEqualTo(1);
        assertThat(next.contexts.getFirst().rejectedCandidates()).isEmpty();
    }

    @Test
    void duplicateCandidateIdSourceIsADefectInsteadOfAnInfiniteLoop() {
        UUID fixed = UUID.randomUUID();
        steps.decisions.add(EvaluationDecision.REGENERATE);
        var fixedExecutor = new RetryingRoleplayTurnExecutor(() -> fixed);
        assertThatThrownBy(() -> fixedExecutor.execute(TURN, steps))
                .isInstanceOf(IllegalStateException.class);
        assertThat(steps.calls).containsExactly("A", "G", "E");
    }

    private static String stage(AiOperation operation) {
        return switch (operation) {
            case CAUSE_ANALYSIS -> "A";
            case RESPONSE_GENERATION -> "G";
            default -> "E";
        };
    }

    private static final class ScriptedSteps implements RetryableRoleplayTurnSteps {
        private final List<String> calls = new ArrayList<>();
        private final List<RoleplayRetryContext> contexts = new ArrayList<>();
        private final List<UUID> generatedIds = new ArrayList<>();
        private final List<AnalysisResult> generatedAnalyses = new ArrayList<>();
        private final List<CandidateResponse> evaluatedCandidates = new ArrayList<>();
        private final Deque<EvaluationDecision> decisions = new ArrayDeque<>();
        private EvaluationDecision defaultDecision = EvaluationDecision.PASS;
        private AiOperation failAt;
        private AiFailureType failureType = AiFailureType.TIMEOUT;
        private int failuresRemaining;
        private RetryTarget overrideTarget;
        private boolean wrongGeneratedId;

        @Override
        public AiCallResult<AnalysisResult> analyze(UUID turnId, RoleplayRetryContext retry) {
            record(AiOperation.CAUSE_ANALYSIS, retry);
            if (fails(AiOperation.CAUSE_ANALYSIS)) return failure(AiOperation.CAUSE_ANALYSIS);
            return success(
                    new AnalysisResult(
                            turnId,
                            "ANSWER",
                            "RELATED",
                            "PARTIAL",
                            new AnalysisResult.PrimaryGap("MISSING_EMOTION", null),
                            new AnalysisResult.NextStrategy("ASK", GOAL, null, "S1"),
                            0.9),
                    AiOperation.CAUSE_ANALYSIS);
        }

        @Override
        public AiCallResult<CandidateResponse> generate(
                UUID turnId,
                UUID candidateId,
                AnalysisResult analysis,
                RoleplayRetryContext retry) {
            record(AiOperation.RESPONSE_GENERATION, retry);
            generatedIds.add(candidateId);
            generatedAnalyses.add(analysis);
            if (fails(AiOperation.RESPONSE_GENERATION))
                return failure(AiOperation.RESPONSE_GENERATION);
            return success(
                    new CandidateResponse(
                            wrongGeneratedId ? UUID.randomUUID() : candidateId,
                            turnId,
                            "친구는 어떤 기분일까?",
                            "QUESTION",
                            "ASK",
                            GOAL,
                            "S1",
                            retry.rejectedCandidates().stream()
                                    .map(r -> r.candidate().candidateId())
                                    .toList()),
                    AiOperation.RESPONSE_GENERATION);
        }

        @Override
        public AiCallResult<EvaluationResult> evaluate(
                UUID turnId,
                AnalysisResult analysis,
                CandidateResponse candidate,
                RoleplayRetryContext retry) {
            record(AiOperation.RESPONSE_EVALUATION, retry);
            evaluatedCandidates.add(candidate);
            if (fails(AiOperation.RESPONSE_EVALUATION))
                return failure(AiOperation.RESPONSE_EVALUATION);
            EvaluationDecision decision =
                    decisions.isEmpty() ? defaultDecision : decisions.removeFirst();
            RetryTarget target =
                    switch (decision) {
                        case REGENERATE, SAFETY_REGENERATE -> RetryTarget.RESPONSE_GENERATION;
                        case REANALYZE -> RetryTarget.CAUSE_ANALYSIS;
                        case CONFIRM_INPUT -> RetryTarget.INPUT_CONFIRMATION;
                        case SAFETY_ESCALATION -> RetryTarget.SAFETY_ESCALATION;
                        case RETRY_EVALUATION -> RetryTarget.RESPONSE_EVALUATION;
                        case SAFE_FALLBACK -> RetryTarget.SAFE_FALLBACK;
                        default -> null;
                    };
            return success(
                    new EvaluationResult(
                            candidate.candidateId(),
                            decision == EvaluationDecision.PASS,
                            decision,
                            overrideTarget == null ? target : overrideTarget,
                            0,
                            decision == EvaluationDecision.PASS
                                    ? List.of()
                                    : List.of("SYNTHETIC_FAILURE"),
                            new RevisionInstruction(
                                    List.of(),
                                    List.of("focus on emotion"),
                                    List.of(),
                                    List.of("ask one question"))),
                    AiOperation.RESPONSE_EVALUATION);
        }

        private void record(AiOperation operation, RoleplayRetryContext retry) {
            calls.add(stage(operation));
            contexts.add(retry);
        }

        private boolean fails(AiOperation operation) {
            if (operation == failAt && failuresRemaining > 0) {
                failuresRemaining--;
                return true;
            }
            return false;
        }

        private <T> AiCallResult<T> failure(AiOperation operation) {
            return new AiCallResult.Failure<>(
                    new AiFailure(failureType, null), metadata(operation));
        }

        private <T> AiCallResult<T> success(T data, AiOperation operation) {
            return new AiCallResult.Success<>(data, metadata(operation));
        }

        private AiCallMetadata metadata(AiOperation operation) {
            return new AiCallMetadata(
                    UUID.randomUUID(),
                    operation,
                    "fake",
                    "fake-model",
                    "synthetic/v1",
                    "v1",
                    null,
                    1,
                    contexts.getLast().stageAttempt(),
                    null,
                    null,
                    "stop");
        }
    }
}
