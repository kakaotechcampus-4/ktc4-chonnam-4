package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRetryContext.RejectedCandidate;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.RecoveryReason;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bounded retries and cooperative deadline checks. Use RoleplayTurnRunner to bound blocking waits.
 */
public final class RetryingRoleplayTurnExecutor {
    private static final int MAX_ANALYSIS_CALLS_PER_TURN = 3;
    private static final int MAX_TECHNICAL_ATTEMPTS_PER_CANDIDATE = 3;
    private static final int MAX_CANDIDATES_PER_TURN = 3;
    private final Supplier<UUID> candidateIds;
    private final RoleplayRetryMetrics metrics;

    public RetryingRoleplayTurnExecutor() {
        this(UUID::randomUUID);
    }

    public RetryingRoleplayTurnExecutor(Supplier<UUID> candidateIds) {
        this(candidateIds, RoleplayRetryMetrics.NONE);
    }

    public RetryingRoleplayTurnExecutor(Supplier<UUID> candidateIds, RoleplayRetryMetrics metrics) {
        this.candidateIds = Objects.requireNonNull(candidateIds);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public RoleplayTurnResult execute(UUID turnId, RetryableRoleplayTurnSteps steps) {
        return execute(turnId, steps, RoleplayTurnDeadline.start());
    }

    public RoleplayTurnResult execute(
            UUID turnId, RetryableRoleplayTurnSteps steps, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(turnId);
        Objects.requireNonNull(steps);
        Objects.requireNonNull(deadline);
        State state = new State(steps, deadline);
        long started = System.nanoTime();
        RoleplayRetryMetrics.Completion completion = RoleplayRetryMetrics.Completion.ERROR;
        try {
            RoleplayTurnResult result = executeActive(turnId, state, deadline);
            completion =
                    result instanceof RoleplayTurnResult.Ready
                            ? RoleplayRetryMetrics.Completion.APPROVED
                            : RoleplayRetryMetrics.Completion.valueOf(
                                    ((RoleplayTurnResult.RecoveryRequired) result).reason().name());
            return result;
        } catch (RoleplayTurnDeadline.Expired expired) {
            deadline.cancel();
            completion = RoleplayRetryMetrics.Completion.TIMED_OUT;
            return new RoleplayTurnResult.TimedOut();
        } finally {
            var ended = completion;
            observe(
                    () ->
                            metrics.finished(
                                    ended,
                                    state.analysisCalls,
                                    state.generationCalls,
                                    state.evaluationCalls,
                                    state.candidateCount,
                                    System.nanoTime() - started));
        }
    }

    private void observe(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            // Observability must not change retries or discard an approved response.
        }
    }

    private RoleplayTurnResult executeActive(
            UUID turnId, State state, RoleplayTurnDeadline deadline) {
        RoleplayTurnExecutor single = new RoleplayTurnExecutor(state::candidateId);
        while (true) {
            deadline.requireActive();
            RoleplayTurnResult result = single.execute(turnId, state);
            deadline.requireActive();
            if (result instanceof RoleplayTurnResult.Ready) return result;
            if (result instanceof RoleplayTurnResult.CallFailed failed) {
                boolean retryAllowed =
                        failed.failure().retryable() && state.canRetry(failed.operation());
                observe(
                        () ->
                                metrics.failed(
                                        failed.operation(), failed.failure().type(), retryAllowed));
                if (!failed.failure().retryable()) {
                    return recovery(failed.operation(), RecoveryReason.NON_RETRYABLE_FAILURE);
                }
                if (!state.canRetry(failed.operation())) {
                    return recovery(failed.operation(), RecoveryReason.LIMIT_REACHED);
                }
                state.discardFailedOutput(failed.operation());
                continue;
            }

            EvaluationResult evaluation = ((RoleplayTurnResult.Rejected) result).evaluation();
            observe(() -> metrics.rejected(evaluation.decision()));
            RetryTarget target = modelTarget(evaluation.decision());
            if (target == null
                    || evaluation.retryTarget() != target
                    || Boolean.TRUE.equals(evaluation.safeToSend())
                    || evaluation.failureCodes().isEmpty()) {
                return recovery(
                        AiOperation.RESPONSE_EVALUATION, RecoveryReason.INCONSISTENT_EVALUATION);
            }
            if (target == RetryTarget.INPUT_CONFIRMATION
                    || target == RetryTarget.SAFETY_ESCALATION) {
                return new RoleplayTurnResult.RecoveryRequired(
                        target, AiOperation.RESPONSE_EVALUATION, RecoveryReason.MODEL_REQUESTED);
            }
            if (target == RetryTarget.CAUSE_ANALYSIS
                    && state.analysisCalls >= MAX_ANALYSIS_CALLS_PER_TURN) {
                return recovery(AiOperation.CAUSE_ANALYSIS, RecoveryReason.LIMIT_REACHED);
            }
            if (state.candidateCount >= MAX_CANDIDATES_PER_TURN) {
                return recovery(AiOperation.RESPONSE_GENERATION, RecoveryReason.LIMIT_REACHED);
            }
            state.rejections.add(new RejectedCandidate(state.candidate.data(), evaluation));
            if (target == RetryTarget.CAUSE_ANALYSIS) state.analysis = null;
            state.nextCandidate();
        }
    }

    private static RetryTarget modelTarget(EvaluationDecision decision) {
        return switch (decision) {
            case REGENERATE, SAFETY_REGENERATE -> RetryTarget.RESPONSE_GENERATION;
            case REANALYZE -> RetryTarget.CAUSE_ANALYSIS;
            case CONFIRM_INPUT -> RetryTarget.INPUT_CONFIRMATION;
            case SAFETY_ESCALATION -> RetryTarget.SAFETY_ESCALATION;
            default -> null;
        };
    }

    private static RoleplayTurnResult.RecoveryRequired recovery(
            AiOperation operation, RecoveryReason reason) {
        return new RoleplayTurnResult.RecoveryRequired(
                operation == AiOperation.CAUSE_ANALYSIS
                        ? RetryTarget.INPUT_CONFIRMATION
                        : RetryTarget.SAFE_FALLBACK,
                operation,
                reason);
    }

    /** Local to execute(): counters and cached successes never leak into another request. */
    private final class State implements RoleplayTurnSteps {
        private final RetryableRoleplayTurnSteps steps;
        private final RoleplayTurnDeadline deadline;
        private final List<RejectedCandidate> rejections = new ArrayList<>();
        private AiCallResult.Success<AnalysisResult> analysis;
        private AiCallResult.Success<CandidateResponse> candidate;
        private UUID currentCandidateId;
        private int analysisCalls;
        private int generationCalls;
        private int evaluationCalls;
        private int candidateCount;
        private int generationCallsForCandidate;
        private int evaluationCallsForCandidate;

        private State(RetryableRoleplayTurnSteps steps, RoleplayTurnDeadline deadline) {
            this.steps = steps;
            this.deadline = deadline;
        }

        private UUID candidateId() {
            if (currentCandidateId == null) {
                UUID next = Objects.requireNonNull(candidateIds.get());
                if (rejections.stream().anyMatch(r -> r.candidate().candidateId().equals(next))) {
                    throw new IllegalStateException("Candidate ID must be new for regeneration");
                }
                currentCandidateId = next;
                candidateCount++;
            }
            return currentCandidateId;
        }

        @Override
        public AiCallResult<AnalysisResult> analyze(UUID turnId) {
            if (analysis != null) return analysis;
            analysisCalls++;
            AiCallResult<AnalysisResult> result =
                    call(
                            AiOperation.CAUSE_ANALYSIS,
                            () -> steps.analyze(turnId, context(analysisCalls)));
            if (result instanceof AiCallResult.Success<AnalysisResult> success) analysis = success;
            return result;
        }

        @Override
        public AiCallResult<CandidateResponse> generate(
                UUID turnId, UUID candidateId, AnalysisResult analyzed) {
            if (candidate != null) return candidate;
            generationCalls++;
            generationCallsForCandidate++;
            AiCallResult<CandidateResponse> result =
                    call(
                            AiOperation.RESPONSE_GENERATION,
                            () ->
                                    steps.generate(
                                            turnId,
                                            candidateId,
                                            analyzed,
                                            context(generationCallsForCandidate)));
            if (result instanceof AiCallResult.Success<CandidateResponse> success)
                candidate = success;
            return result;
        }

        @Override
        public AiCallResult<EvaluationResult> evaluate(
                UUID turnId, AnalysisResult analyzed, CandidateResponse generated) {
            evaluationCalls++;
            evaluationCallsForCandidate++;
            return call(
                    AiOperation.RESPONSE_EVALUATION,
                    () ->
                            steps.evaluate(
                                    turnId,
                                    analyzed,
                                    generated,
                                    context(evaluationCallsForCandidate)));
        }

        private <T> AiCallResult<T> call(
                AiOperation operation, Supplier<AiCallResult<T>> invocation) {
            long started = System.nanoTime();
            String outcome = "ERROR";
            try {
                AiCallResult<T> result = deadline.withinBudget(invocation);
                outcome =
                        result instanceof AiCallResult.Failure<T> failed
                                ? failed.failure().type().name()
                                : "SUCCESS";
                return result;
            } catch (RoleplayTurnDeadline.Expired expired) {
                outcome = "DEADLINE_EXPIRED";
                throw expired;
            } finally {
                String ended = outcome;
                observe(() -> metrics.call(operation, ended, System.nanoTime() - started));
            }
        }

        private RoleplayRetryContext context(int stageAttempt) {
            return new RoleplayRetryContext(
                    new AiAttemptContext(analysisCalls, generationCalls, evaluationCalls),
                    stageAttempt,
                    rejections,
                    deadline.callBudget());
        }

        private boolean canRetry(AiOperation operation) {
            return switch (operation) {
                case CAUSE_ANALYSIS -> analysisCalls < MAX_ANALYSIS_CALLS_PER_TURN;
                case RESPONSE_GENERATION ->
                        generationCallsForCandidate < MAX_TECHNICAL_ATTEMPTS_PER_CANDIDATE;
                case RESPONSE_EVALUATION ->
                        evaluationCallsForCandidate < MAX_TECHNICAL_ATTEMPTS_PER_CANDIDATE;
                default -> false;
            };
        }

        private void discardFailedOutput(AiOperation operation) {
            if (operation == AiOperation.CAUSE_ANALYSIS) analysis = null;
            if (operation == AiOperation.RESPONSE_GENERATION) candidate = null;
        }

        private void nextCandidate() {
            candidate = null;
            currentCandidateId = null;
            generationCallsForCandidate = 0;
            evaluationCallsForCandidate = 0;
        }
    }
}
