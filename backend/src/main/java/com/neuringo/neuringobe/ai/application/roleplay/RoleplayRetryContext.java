package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallBudget;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.List;
import java.util.Objects;

/** 요청 내부의 재시도 자료이며 로그나 아동 응답에 노출하지 않는다. */
public record RoleplayRetryContext(
        AiAttemptContext attempts,
        int stageAttempt,
        List<RejectedCandidate> rejectedCandidates,
        AiCallBudget callBudget) {
    public RoleplayRetryContext(
            AiAttemptContext attempts,
            int stageAttempt,
            List<RejectedCandidate> rejectedCandidates) {
        this(attempts, stageAttempt, rejectedCandidates, null);
    }

    public RoleplayRetryContext {
        Objects.requireNonNull(attempts);
        if (stageAttempt < 1) throw new IllegalArgumentException("stageAttempt must be positive");
        rejectedCandidates = List.copyOf(rejectedCandidates);
    }

    public record RejectedCandidate(
            AnalysisResult analysis, CandidateResponse candidate, EvaluationResult evaluation) {
        public RejectedCandidate {
            Objects.requireNonNull(analysis);
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(evaluation);
            if (!analysis.turnId().equals(candidate.turnId())
                    || !candidate.candidateId().equals(evaluation.candidateId())
                    || evaluation.canDeliver()) {
                throw new IllegalArgumentException(
                        "Only a matching analysis, candidate and rejected evaluation are allowed");
            }
        }
    }
}
