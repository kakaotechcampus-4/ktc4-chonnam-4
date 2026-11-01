package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.AnalysisRetry;
import com.neuringo.neuringobe.ai.application.prompt.GenerationRetry;
import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRetryContext.RejectedCandidate;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 표준화·안전 검사를 통과한 입력을 요청별 분석·생성·검수 호출에 연결한다. */
public final class StructuredRoleplayTurnSteps implements RetryableRoleplayTurnSteps {
    private final RoleplayPromptFactory prompts;
    private final StructuredLlmExecutor llm;
    private final AiTraceContext trace;
    private final RoleplayTurnInput input;
    private final Supplier<UUID> analysisIds;
    private final GoalObservationInput observations;
    private UUID currentAnalysisId;
    private boolean invalidAnalysisOutput;

    public StructuredRoleplayTurnSteps(
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm,
            AiTraceContext trace,
            RoleplayTurnInput input,
            GoalObservationInput observations) {
        this(prompts, llm, trace, input, UUID::randomUUID, observations);
    }

    public StructuredRoleplayTurnSteps(
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm,
            AiTraceContext trace,
            RoleplayTurnInput input,
            Supplier<UUID> analysisIds,
            GoalObservationInput observations) {
        this.prompts = Objects.requireNonNull(prompts);
        this.llm = Objects.requireNonNull(llm);
        this.trace = Objects.requireNonNull(trace);
        this.input = Objects.requireNonNull(input);
        this.analysisIds = Objects.requireNonNull(analysisIds);
        this.observations = Objects.requireNonNull(observations);
        observations.requireMatches(input);
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
                        analysisRetry(retry),
                        observations);
        var result =
                llm.execute(
                        prepared.request().withCallBudget(retry.callBudget()), prepared.parser());
        if (result instanceof AiCallResult.Success<?>) invalidAnalysisOutput = false;
        else if (result instanceof AiCallResult.Failure<?> failed
                && failed.failure().type() == AiFailureType.INVALID_OUTPUT_FORMAT)
            invalidAnalysisOutput = true;
        return result;
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
        return llm.execute(
                prepared.request().withCallBudget(retry.callBudget()), prepared.parser());
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
                        candidate,
                        observations);
        return llm.execute(
                prepared.request().withCallBudget(retry.callBudget()), prepared.parser());
    }

    private AnalysisRetry analysisRetry(RoleplayRetryContext retry) {
        RejectedCandidate last = latest(retry);
        boolean rejectedReview =
                last != null && last.evaluation().decision() == EvaluationDecision.REANALYZE;
        if (!rejectedReview && !invalidAnalysisOutput) return null;
        var codes = new ArrayList<String>();
        var focus = new ArrayList<String>();
        var avoid = new ArrayList<String>();
        if (rejectedReview) {
            codes.addAll(last.evaluation().failureCodes());
            var revision = last.evaluation().revisionInstruction();
            if (revision != null) {
                focus.addAll(revision.change());
                focus.addAll(revision.required());
                avoid.addAll(revision.avoid());
            }
        }
        if (invalidAnalysisOutput) {
            codes.add("INVALID_OUTPUT_FORMAT");
            focus.add("필수 필드·승인 참조·분석 식별자와 허용 코드를 다시 확인한다.");
            focus.add("목표별 정답 제공 이력을 확인하고 이미 정답을 제공한 목표를 다시 유도하지 않는다.");
            focus.add("같은 목표의 지원을 낮춰 반복 유도하지 않는다.");
            focus.add("최대 턴에 도달했으면 CLOSE_ROLEPLAY로 마무리한다.");
            avoid.add("이전 출력이 검증을 통과했다고 가정하지 않는다.");
        }
        return new AnalysisRetry(
                codes.stream().distinct().toList(),
                focus.stream().distinct().toList(),
                avoid.stream().distinct().toList());
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
