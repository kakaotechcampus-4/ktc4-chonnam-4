package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;

/** Content-free observations of the LLM retry unit, not HTTP delivery or DB confirmation. */
public interface RoleplayRetryMetrics {
    RoleplayRetryMetrics NONE = new RoleplayRetryMetrics() {};

    enum Completion {
        APPROVED,
        TIMED_OUT,
        ERROR,
        LIMIT_REACHED,
        NON_RETRYABLE_FAILURE,
        MODEL_REQUESTED,
        INPUT_SAFETY_REJECTED,
        INCONSISTENT_EVALUATION
    }

    default void call(AiOperation operation, String outcome, long elapsedNanos) {}

    default void failed(AiOperation operation, AiFailureType failure, boolean retryAllowed) {}

    default void rejected(EvaluationDecision decision) {}

    default void finished(
            Completion completion,
            int analysis,
            int generation,
            int evaluation,
            int candidates,
            long elapsedNanos) {}
}
