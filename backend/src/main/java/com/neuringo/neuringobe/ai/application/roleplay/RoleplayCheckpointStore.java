package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.Objects;
import java.util.UUID;

/**
 * Adapter must atomically write the approved turn, advance the session checkpoint and link the
 * idempotency result in one short transaction, after authorization and processing ownership checks.
 * Never hold that transaction during an AI call. A deadline/cancellation fence and matching
 * expected version/turn number are required at commit. This interface supplies no persistence
 * implementation.
 */
public interface RoleplayCheckpointStore {
    CommitResult commit(RoleplayCheckpointCommand command, RoleplayTurnDeadline deadline);

    LookupResult findConfirmed(RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline);

    sealed interface LookupResult {}

    record Found(Committed result) implements LookupResult {
        public Found {
            Objects.requireNonNull(result);
        }
    }

    record Absent() implements LookupResult {}

    sealed interface CommitResult {}

    /** Also used for an exact duplicate; no new counter, goal update or reward may be applied. */
    record Committed(
            UUID turnId, UUID candidateId, long version, boolean replayed, String responseText)
            implements CommitResult {
        public Committed {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(candidateId);
            if (responseText == null || responseText.isBlank())
                throw new IllegalArgumentException("Approved response required");
            if (version < 1)
                throw new IllegalArgumentException("Committed version must be positive");
        }

        @Override
        public String toString() {
            return "Committed[content=redacted]";
        }
    }

    record Conflict() implements CommitResult, LookupResult {}

    record Expired() implements CommitResult, LookupResult {}
}
