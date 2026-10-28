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

/** 내부 실행 결과다. Ready도 저장 또는 아동 전달까지 완료된 상태는 아니다. */
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

    /** 입력 당시 상태는 서버가 붙인다. 자료 지문은 요청 중에만 사용하고 공개하지 않는다. */
    record SpokenReady(
            Ready ready,
            String canonicalUtterance,
            SynthesizedSpeech speech,
            String approvedContextFingerprint,
            RoleplayInputContext inputContext)
            implements RoleplayTurnResult {
        public SpokenReady(Ready ready, String canonicalUtterance, SynthesizedSpeech speech) {
            this(ready, canonicalUtterance, speech, null, null);
        }

        public SpokenReady(
                Ready ready,
                String canonicalUtterance,
                SynthesizedSpeech speech,
                String approvedContextFingerprint) {
            this(ready, canonicalUtterance, speech, approvedContextFingerprint, null);
        }

        public SpokenReady {
            Objects.requireNonNull(ready);
            if (canonicalUtterance == null || canonicalUtterance.isBlank())
                throw new IllegalArgumentException("Canonical utterance required");
            if (approvedContextFingerprint != null
                    && !approvedContextFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("분석 자료의 지문이 올바르지 않습니다.");
            }
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
            if (!evaluation.matchesObservationContract(analysis)) {
                throw new IllegalArgumentException("관찰 분석과 검수의 형식 또는 분석 식별자가 일치하지 않습니다.");
            }
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
