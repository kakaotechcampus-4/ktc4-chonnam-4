package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.Optional;

/** Ephemeral ownership only. No utterance, fingerprint, request row, TTL or event is persisted. */
public interface RoleplayRequestGuard {
    Optional<Lease> tryAcquire(RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline);

    final class CapacityUnavailable extends RuntimeException {
        public CapacityUnavailable() {
            super("Roleplay processing capacity unavailable");
        }
    }

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
