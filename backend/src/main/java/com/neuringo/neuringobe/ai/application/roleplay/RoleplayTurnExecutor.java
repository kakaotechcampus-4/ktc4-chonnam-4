package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** One attempt only. The caller owns input validation, retries, persistence and delivery. */
public final class RoleplayTurnExecutor {
    private final Supplier<UUID> candidateIds;

    public RoleplayTurnExecutor() {
        this(UUID::randomUUID);
    }

    public RoleplayTurnExecutor(Supplier<UUID> candidateIds) {
        this.candidateIds = Objects.requireNonNull(candidateIds);
    }

    public RoleplayTurnResult execute(UUID turnId, RoleplayTurnSteps steps) {
        Objects.requireNonNull(turnId);
        Objects.requireNonNull(steps);

        AiCallResult<AnalysisResult> analyzed = steps.analyze(turnId);
        if (analyzed instanceof AiCallResult.Failure<AnalysisResult> failed) {
            return failed(AiOperation.CAUSE_ANALYSIS, failed);
        }
        var analysisSuccess = (AiCallResult.Success<AnalysisResult>) analyzed;
        AnalysisResult analysis = analysisSuccess.data();
        if (!turnId.equals(analysis.turnId())) {
            return invalid(AiOperation.CAUSE_ANALYSIS, analysisSuccess.metadata());
        }

        UUID candidateId = Objects.requireNonNull(candidateIds.get());
        AiCallResult<CandidateResponse> generated = steps.generate(turnId, candidateId, analysis);
        if (generated instanceof AiCallResult.Failure<CandidateResponse> failed) {
            return failed(AiOperation.RESPONSE_GENERATION, failed);
        }
        var candidateSuccess = (AiCallResult.Success<CandidateResponse>) generated;
        CandidateResponse candidate = candidateSuccess.data();
        if (!turnId.equals(candidate.turnId()) || !candidateId.equals(candidate.candidateId())) {
            return invalid(AiOperation.RESPONSE_GENERATION, candidateSuccess.metadata());
        }

        AiCallResult<EvaluationResult> evaluated = steps.evaluate(turnId, analysis, candidate);
        if (evaluated instanceof AiCallResult.Failure<EvaluationResult> failed) {
            return failed(AiOperation.RESPONSE_EVALUATION, failed);
        }
        var evaluationSuccess = (AiCallResult.Success<EvaluationResult>) evaluated;
        EvaluationResult evaluation = evaluationSuccess.data();
        if (!candidateId.equals(evaluation.candidateId())) {
            return invalid(AiOperation.RESPONSE_EVALUATION, evaluationSuccess.metadata());
        }
        if (!evaluation.canDeliver()) {
            return new RoleplayTurnResult.Rejected(evaluation);
        }
        return new RoleplayTurnResult.Ready(analysis, candidate, evaluation);
    }

    private RoleplayTurnResult.CallFailed failed(
            AiOperation operation, AiCallResult.Failure<?> failed) {
        return new RoleplayTurnResult.CallFailed(operation, failed.failure(), failed.metadata());
    }

    private RoleplayTurnResult.CallFailed invalid(AiOperation operation, AiCallMetadata metadata) {
        return new RoleplayTurnResult.CallFailed(
                operation, new AiFailure(AiFailureType.INVALID_OUTPUT_FORMAT, null), metadata);
    }
}
