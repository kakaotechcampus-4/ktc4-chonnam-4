package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.List;
import java.util.Objects;

/** Ephemeral context for prompt adapters. Never log or expose it in child HTTP responses. */
public record RoleplayRetryContext(
        AiAttemptContext attempts, int stageAttempt, List<RejectedCandidate> rejectedCandidates) {
    public RoleplayRetryContext {
        Objects.requireNonNull(attempts);
        if (stageAttempt < 1) throw new IllegalArgumentException("stageAttempt must be positive");
        rejectedCandidates = List.copyOf(rejectedCandidates);
    }

    public record RejectedCandidate(CandidateResponse candidate, EvaluationResult evaluation) {
        public RejectedCandidate {
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(evaluation);
            if (!candidate.candidateId().equals(evaluation.candidateId())
                    || evaluation.canDeliver()) {
                throw new IllegalArgumentException(
                        "Only a matching rejected evaluation is allowed");
            }
        }
    }
}
