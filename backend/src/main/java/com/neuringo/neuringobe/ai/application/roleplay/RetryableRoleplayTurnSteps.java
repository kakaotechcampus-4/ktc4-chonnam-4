package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.UUID;

/** One shared turn context; adapters map revision instructions into the supplied prompt factory. */
public interface RetryableRoleplayTurnSteps {
    AiCallResult<AnalysisResult> analyze(UUID turnId, RoleplayRetryContext retry);

    AiCallResult<CandidateResponse> generate(
            UUID turnId, UUID candidateId, AnalysisResult analysis, RoleplayRetryContext retry);

    AiCallResult<EvaluationResult> evaluate(
            UUID turnId,
            AnalysisResult analysis,
            CandidateResponse candidate,
            RoleplayRetryContext retry);
}
