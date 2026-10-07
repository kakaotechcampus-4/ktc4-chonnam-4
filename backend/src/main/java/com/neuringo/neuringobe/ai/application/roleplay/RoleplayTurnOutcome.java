package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.ApprovedNotice;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.Kind;
import java.util.Objects;
import java.util.UUID;

/** Internal delivery plan, not a child HTTP DTO or a persistence operation. */
public sealed interface RoleplayTurnOutcome {
    enum AssessmentEligibility {
        ELIGIBLE,
        NOT_ASSESSABLE
    }

    enum Action {
        REQUEST_NEW_INPUT,
        RETRY_TURN,
        STOP_DIALOGUE
    }

    enum Retention {
        FOLLOW_STANDARD_POLICY,
        DO_NOT_STORE
    }

    AssessmentEligibility assessmentEligibility();

    /**
     * Transient audio and validated learner text; API resource/byte representation is still
     * separate.
     */
    record SpokenResponse(
            ApprovedResponse response, String canonicalUtterance, SynthesizedSpeech speech)
            implements RoleplayTurnOutcome {
        public SpokenResponse {
            Objects.requireNonNull(response);
            if (canonicalUtterance == null || canonicalUtterance.isBlank())
                throw new IllegalArgumentException("Canonical utterance required");
        }

        @Override
        public AssessmentEligibility assessmentEligibility() {
            return AssessmentEligibility.ELIGIBLE;
        }

        @Override
        public String toString() {
            return "SpokenResponse[candidateId="
                    + response.candidateId()
                    + ", hasAudio="
                    + (speech != null)
                    + "]";
        }
    }

    record ApprovedResponse(UUID candidateId, String text) implements RoleplayTurnOutcome {
        public ApprovedResponse {
            Objects.requireNonNull(candidateId);
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Text required");
        }

        @Override
        public AssessmentEligibility assessmentEligibility() {
            return AssessmentEligibility.ELIGIBLE;
        }
    }

    /** Routing stays explicit even when deployment lacks an approved notice. */
    sealed interface Recovery extends RoleplayTurnOutcome {
        Action action();

        Retention retention();

        @Override
        default AssessmentEligibility assessmentEligibility() {
            return AssessmentEligibility.NOT_ASSESSABLE;
        }
    }

    record InputRetry(ApprovedNotice notice) implements Recovery {
        public InputRetry {
            Objects.requireNonNull(notice);
        }

        @Override
        public Action action() {
            return Action.REQUEST_NEW_INPUT;
        }

        @Override
        public Retention retention() {
            return Retention.DO_NOT_STORE;
        }
    }

    record InputNoticeUnavailable() implements Recovery {
        @Override
        public Action action() {
            return Action.REQUEST_NEW_INPUT;
        }

        @Override
        public Retention retention() {
            return Retention.DO_NOT_STORE;
        }
    }

    record Guidance(ApprovedNotice notice, Action action) implements Recovery {
        public Guidance {
            Objects.requireNonNull(notice);
            Objects.requireNonNull(action);
            if (notice.kind() == Kind.SAFETY_ESCALATION_CHILD
                    || notice.kind() == Kind.SAFETY_ESCALATION_TEACHER
                    || action == Action.STOP_DIALOGUE) {
                throw new IllegalArgumentException("Safety stop requires both audience notices");
            }
        }

        @Override
        public Retention retention() {
            return Retention.FOLLOW_STANDARD_POLICY;
        }
    }

    /** Neither raw speech, candidate text nor event metadata is carried across this boundary. */
    record SafetyStop(ApprovedNotice childNotice, ApprovedNotice teacherNotice)
            implements Recovery {
        public SafetyStop {
            Objects.requireNonNull(childNotice);
            Objects.requireNonNull(teacherNotice);
            if (childNotice.kind() != Kind.SAFETY_ESCALATION_CHILD
                    || teacherNotice.kind() != Kind.SAFETY_ESCALATION_TEACHER) {
                throw new IllegalArgumentException("Safety notice audiences must match");
            }
        }

        @Override
        public Action action() {
            return Action.STOP_DIALOGUE;
        }

        @Override
        public Retention retention() {
            return Retention.DO_NOT_STORE;
        }
    }

    record NoticeUnavailable(Action action) implements Recovery {
        public NoticeUnavailable {
            Objects.requireNonNull(action);
        }

        @Override
        public Retention retention() {
            return action == Action.STOP_DIALOGUE
                    ? Retention.DO_NOT_STORE
                    : Retention.FOLLOW_STANDARD_POLICY;
        }
    }
}
