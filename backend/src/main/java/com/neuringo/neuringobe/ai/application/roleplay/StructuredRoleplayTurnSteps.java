package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.AnalysisRetry;
import com.neuringo.neuringobe.ai.application.prompt.GenerationRetry;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRetryContext.RejectedCandidate;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Request-scoped binding. Input has already passed canonicalization and safety checks. */
public final class StructuredRoleplayTurnSteps implements RetryableRoleplayTurnSteps {
    private final RoleplayPromptFactory prompts;
    private final StructuredLlmExecutor llm;
    private final AiTraceContext trace;
    private final RoleplayTurnInput input;
    private final Supplier<UUID> analysisIds;
    private UUID currentAnalysisId;

    public StructuredRoleplayTurnSteps(
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm,
            AiTraceContext trace,
            RoleplayTurnInput input) {
        this(prompts, llm, trace, input, UUID::randomUUID);
    }

    public StructuredRoleplayTurnSteps(
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm,
            AiTraceContext trace,
            RoleplayTurnInput input,
            Supplier<UUID> analysisIds) {
        this.prompts = Objects.requireNonNull(prompts);
        this.llm = Objects.requireNonNull(llm);
        this.trace = Objects.requireNonNull(trace);
        this.input = Objects.requireNonNull(input);
        this.analysisIds = Objects.requireNonNull(analysisIds);
        requireTurn(trace.turnId());
    }

    @Override
    public AiCallResult<AnalysisResult> analyze(UUID turnId, RoleplayRetryContext retry) {
        requireTurn(turnId);
        currentAnalysisId = Objects.requireNonNull(analysisIds.get());
        var prepared =
                prompts.causeAnalysis(
                        stageTrace(null),
                        retry.attempts(),
                        retry.stageAttempt(),
                        input,
                        analysisRetry(retry));
        return llm.execute(prepared.request(), prepared.parser());
    }

    @Override
    public AiCallResult<CandidateResponse> generate(
            UUID turnId, UUID candidateId, AnalysisResult analysis, RoleplayRetryContext retry) {
        requireTurn(turnId);
        var prepared =
                prompts.responseGeneration(
                        stageTrace(candidateId),
                        retry.attempts(),
                        retry.stageAttempt(),
                        input,
                        analysis,
                        candidateId,
                        generationRetry(retry));
        return llm.execute(prepared.request(), prepared.parser());
    }

    @Override
    public AiCallResult<EvaluationResult> evaluate(
            UUID turnId,
            AnalysisResult analysis,
            CandidateResponse candidate,
            RoleplayRetryContext retry) {
        requireTurn(turnId);
        var prepared =
                prompts.responseEvaluation(
                        stageTrace(candidate.candidateId()),
                        retry.attempts(),
                        retry.stageAttempt(),
                        input,
                        analysis,
                        candidate);
        return llm.execute(prepared.request(), prepared.parser());
    }

    private AnalysisRetry analysisRetry(RoleplayRetryContext retry) {
        RejectedCandidate last = latest(retry);
        if (last == null || last.evaluation().decision() != EvaluationDecision.REANALYZE)
            return null;
        var revision = last.evaluation().revisionInstruction();
        List<String> focus =
                revision == null
                        ? List.of()
                        : Stream.concat(revision.change().stream(), revision.required().stream())
                                .distinct()
                                .toList();
        return new AnalysisRetry(
                last.evaluation().failureCodes(),
                focus,
                revision == null ? List.of() : revision.avoid());
    }

    private GenerationRetry generationRetry(RoleplayRetryContext retry) {
        RejectedCandidate last = latest(retry);
        if (last == null) return null;
        var decision = last.evaluation().decision();
        if (decision != EvaluationDecision.REGENERATE
                && decision != EvaluationDecision.SAFETY_REGENERATE) return null;
        return new GenerationRetry(
                retry.rejectedCandidates().stream()
                        .map(
                                rejected ->
                                        new GenerationRetry.FailedCandidate(
                                                rejected.candidate().candidateId(),
                                                rejected.candidate().text()))
                        .toList(),
                last.evaluation().failureCodes(),
                last.evaluation().revisionInstruction());
    }

    private static RejectedCandidate latest(RoleplayRetryContext retry) {
        return retry.rejectedCandidates().isEmpty() ? null : retry.rejectedCandidates().getLast();
    }

    private AiTraceContext stageTrace(UUID candidateId) {
        if (currentAnalysisId == null) throw new IllegalStateException("Analysis must run first");
        return new AiTraceContext(
                trace.requestId(),
                trace.activityId(),
                trace.classId(),
                trace.childId(),
                trace.scenarioId(),
                trace.scenarioVersion(),
                trace.sessionId(),
                trace.turnId(),
                currentAnalysisId,
                candidateId);
    }

    private void requireTurn(UUID turnId) {
        if (!input.learnerTurn().turnId().equals(turnId)) {
            throw new IllegalArgumentException("Steps are bound to another turn");
        }
    }
}
