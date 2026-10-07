package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.UUID;

/** Execution bindings for one validated turn; adapters own prompts, parsers and shared context. */
public interface RoleplayTurnSteps {
    AiCallResult<AnalysisResult> analyze(UUID turnId);

    AiCallResult<CandidateResponse> generate(
            UUID turnId, UUID candidateId, AnalysisResult analysis);

    AiCallResult<EvaluationResult> evaluate(
            UUID turnId, AnalysisResult analysis, CandidateResponse candidate);
}
