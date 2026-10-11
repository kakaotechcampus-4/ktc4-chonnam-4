package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.Objects;

/** Internal result, not an HTTP DTO. Ready does not mean persisted or delivered to the child. */
public sealed interface RoleplayTurnResult {
    enum InputFailure {
        NO_SPEECH,
        LOW_CONFIDENCE,
        UNKNOWN_CONFIDENCE,
        TRANSCRIPTION_FAILED,
        INVALID_INPUT
    }

    record InputRejected(InputFailure reason) implements RoleplayTurnResult {
        public InputRejected {
            Objects.requireNonNull(reason);
        }
    }

    /** Only a gated candidate can be paired with audio. A null audio means text-only success. */
    record SpokenReady(Ready ready, String canonicalUtterance, SynthesizedSpeech speech)
            implements RoleplayTurnResult {
        public SpokenReady {
            Objects.requireNonNull(ready);
            if (canonicalUtterance == null || canonicalUtterance.isBlank())
                throw new IllegalArgumentException("Canonical utterance required");
        }

        @Override
        public String toString() {
            return "SpokenReady[candidateId="
                    + ready.candidate().candidateId()
                    + ", hasAudio="
                    + (speech != null)
                    + "]";
        }
    }

    /** Server-approved deadline notice. No candidate, assessment or audio is included. */
    record TimedOut() implements RoleplayTurnResult {
        public String message() {
            return "다시 한 번만 말해줄래?";
        }
    }

    enum RecoveryReason {
        LIMIT_REACHED,
        NON_RETRYABLE_FAILURE,
        MODEL_REQUESTED,
        INPUT_SAFETY_REJECTED,
        INCONSISTENT_EVALUATION
    }

    /** Requests a recovery path; contains no generated fallback or rejected candidate text. */
    record RecoveryRequired(RetryTarget target, AiOperation operation, RecoveryReason reason)
            implements RoleplayTurnResult {
        public RecoveryRequired {
            Objects.requireNonNull(target);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(reason);
        }
    }

    record Ready(AnalysisResult analysis, CandidateResponse candidate, EvaluationResult evaluation)
            implements RoleplayTurnResult {
        public Ready {
            Objects.requireNonNull(analysis);
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(evaluation);
            if (!analysis.turnId().equals(candidate.turnId())
                    || !candidate.candidateId().equals(evaluation.candidateId())
                    || !evaluation.canDeliver()) {
                throw new IllegalArgumentException("Only a matching, approved candidate is ready");
            }
        }
    }

    record CallFailed(AiOperation operation, AiFailure failure, AiCallMetadata metadata)
            implements RoleplayTurnResult {
        public CallFailed {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(failure);
            Objects.requireNonNull(metadata);
        }
    }

    record Rejected(EvaluationResult evaluation) implements RoleplayTurnResult {
        public Rejected {
            Objects.requireNonNull(evaluation);
            if (evaluation.canDeliver()) {
                throw new IllegalArgumentException("Approved evaluation cannot be rejected");
            }
        }
    }
}
