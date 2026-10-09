package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.GenerationRetry;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RetryableRoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRetryContext;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 실제 단계(StructuredRoleplayTurnSteps)를 감싸 결과를 관찰한다. 재시도 판단은 그대로 RetryingRoleplayTurnExecutor 가 한다.
 *
 * <p>형식 실패(INVALID_OUTPUT_FORMAT)가 나면 방금 받은 모델 출력과, 같은 입력으로 만든 parser 가 거부한 이유를 샘플로 남긴다. 이유는 출력 검사
 * 메시지 그대로이며 모델 출력 내용은 들어 있지 않다.
 */
final class ObservedTurnSteps implements RetryableRoleplayTurnSteps {

    private final RetryableRoleplayTurnSteps delegate;
    private final RecordingLlmProvider recorder;
    private final RoleplayPromptFactory prompts;
    private final RoleplayTurnInput input;
    private final AiTraceContext trace;
    private final Supplier<UUID> currentAnalysisId;

    private final List<FormatFailure> formatFailures = new ArrayList<>();
    private final List<EvaluationResult> evaluations = new ArrayList<>();
    private final List<CandidateResponse> candidates = new ArrayList<>();
    private AnalysisResult lastAnalysis;

    ObservedTurnSteps(
            RetryableRoleplayTurnSteps delegate,
            RecordingLlmProvider recorder,
            RoleplayPromptFactory prompts,
            RoleplayTurnInput input,
            AiTraceContext trace,
            Supplier<UUID> currentAnalysisId) {
        this.delegate = Objects.requireNonNull(delegate);
        this.recorder = Objects.requireNonNull(recorder);
        this.prompts = Objects.requireNonNull(prompts);
        this.input = Objects.requireNonNull(input);
        this.trace = Objects.requireNonNull(trace);
        this.currentAnalysisId = Objects.requireNonNull(currentAnalysisId);
    }

    @Override
    public AiCallResult<AnalysisResult> analyze(UUID turnId, RoleplayRetryContext retry) {
        int mark = recorder.size();
        AiCallResult<AnalysisResult> result = delegate.analyze(turnId, retry);
        if (result instanceof AiCallResult.Success<AnalysisResult> success) {
            lastAnalysis = success.data();
        }
        observeFormat(
                result,
                mark,
                AiOperation.CAUSE_ANALYSIS,
                () -> prompts.causeAnalysis(stageTrace(null), attempts(), 1, input, null).parser());
        return result;
    }

    @Override
    public AiCallResult<CandidateResponse> generate(
            UUID turnId, UUID candidateId, AnalysisResult analysis, RoleplayRetryContext retry) {
        int mark = recorder.size();
        AiCallResult<CandidateResponse> result =
                delegate.generate(turnId, candidateId, analysis, retry);
        if (result instanceof AiCallResult.Success<CandidateResponse> success) {
            candidates.add(success.data());
        }
        observeFormat(
                result,
                mark,
                AiOperation.RESPONSE_GENERATION,
                () ->
                        prompts.responseGeneration(
                                        stageTrace(candidateId),
                                        attempts(),
                                        1,
                                        input,
                                        analysis,
                                        candidateId,
                                        generationRetry(retry))
                                .parser());
        return result;
    }

    @Override
    public AiCallResult<EvaluationResult> evaluate(
            UUID turnId,
            AnalysisResult analysis,
            CandidateResponse candidate,
            RoleplayRetryContext retry) {
        int mark = recorder.size();
        AiCallResult<EvaluationResult> result =
                delegate.evaluate(turnId, analysis, candidate, retry);
        if (result instanceof AiCallResult.Success<EvaluationResult> success) {
            evaluations.add(success.data());
        }
        observeFormat(
                result,
                mark,
                AiOperation.RESPONSE_EVALUATION,
                () ->
                        prompts.responseEvaluation(
                                        stageTrace(candidate.candidateId()),
                                        attempts(),
                                        1,
                                        input,
                                        analysis,
                                        candidate)
                                .parser());
        return result;
    }

    List<FormatFailure> formatFailures() {
        return List.copyOf(formatFailures);
    }

    List<EvaluationResult> evaluations() {
        return List.copyOf(evaluations);
    }

    List<CandidateResponse> candidates() {
        return List.copyOf(candidates);
    }

    /** 마지막으로 성공한 원인 판단. 복구로 끝난 턴도 기대값을 비교할 수 있게 남긴다. */
    AnalysisResult lastAnalysis() {
        return lastAnalysis;
    }

    private void observeFormat(
            AiCallResult<?> result,
            int mark,
            AiOperation operation,
            Supplier<LlmOutputParser<?>> parser) {
        if (!(result instanceof AiCallResult.Failure<?> failure)
                || failure.failure().type() != AiFailureType.INVALID_OUTPUT_FORMAT) {
            return;
        }
        List<RecordingLlmProvider.Call> calls = recorder.since(mark);
        String content = calls.isEmpty() ? null : calls.getLast().content();
        formatFailures.add(new FormatFailure(operation, reason(parser, content), content));
    }

    /** 같은 입력으로 parser 를 다시 만들어 거부 이유를 얻는다. 실패 원인 분류(코드 블록·잘림·필드 누락 등)에 쓴다. */
    private static String reason(Supplier<LlmOutputParser<?>> parser, String content) {
        if (content == null) return "content unavailable";
        try {
            parser.get().parse(content);
            return "accepted on replay";
        } catch (InvalidLlmOutputException exception) {
            Throwable cause = exception.getCause();
            if (cause == null) return exception.getMessage();
            Throwable root = cause;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            // 출력 레코드 생성자가 던진 검사 메시지(예: "revisionInstruction is required ...")만 붙인다. Jackson 파싱 오류
            // 메시지는
            // 모델 출력 일부를 담으므로 예외 이름만 쓴다.
            boolean ownCheck =
                    root instanceof IllegalArgumentException
                            || root instanceof NullPointerException;
            return exception.getMessage()
                    + " ("
                    + cause.getClass().getSimpleName()
                    + (ownCheck ? ": " + root.getMessage() : "")
                    + ")";
        } catch (RuntimeException exception) {
            return exception.getClass().getSimpleName();
        }
    }

    /** StructuredRoleplayTurnSteps 와 같은 규칙: 직전 판정이 재생성일 때만 실패 후보를 넘긴다. */
    private static GenerationRetry generationRetry(RoleplayRetryContext retry) {
        if (retry.rejectedCandidates().isEmpty()) return null;
        var last = retry.rejectedCandidates().getLast().evaluation();
        if (last.decision() != EvaluationDecision.REGENERATE
                && last.decision() != EvaluationDecision.SAFETY_REGENERATE) return null;
        return new GenerationRetry(
                retry.rejectedCandidates().stream()
                        .map(
                                rejected ->
                                        new GenerationRetry.FailedCandidate(
                                                rejected.candidate().candidateId(),
                                                rejected.candidate().text()))
                        .toList(),
                last.failureCodes(),
                last.revisionInstruction());
    }

    private AiTraceContext stageTrace(UUID candidateId) {
        return new AiTraceContext(
                trace.requestId(),
                trace.activityId(),
                trace.classId(),
                trace.childId(),
                trace.scenarioId(),
                trace.scenarioVersion(),
                trace.sessionId(),
                trace.turnId(),
                currentAnalysisId.get(),
                candidateId);
    }

    private static AiAttemptContext attempts() {
        return new AiAttemptContext(1, 1, 1);
    }

    /** 형식 실패 한 건. content 는 모델이 보낸 원문이다. */
    record FormatFailure(AiOperation operation, String reason, String content) {}
}
