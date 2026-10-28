package com.neuringo.neuringobe.roleplay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestGuard;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryRoleplayRequestGuardTest {
    private final InMemoryRoleplayRequestGuard guard = new InMemoryRoleplayRequestGuard();

    @Test
    void conflictingInputDoesNotReleaseOrReplaceCurrentOwner() {
        var session = UUID.randomUUID();
        var key = UUID.randomUUID();
        var original = identity(session, key, "a".repeat(64));
        try (var lease = guard.tryAcquire(original, RoleplayTurnDeadline.start()).orElseThrow()) {
            assertThatThrownBy(
                            () ->
                                    guard.tryAcquire(
                                            identity(session, key, "b".repeat(64)),
                                            RoleplayTurnDeadline.start()))
                    .isInstanceOf(RoleplayRequestGuard.InputConflict.class)
                    .hasMessageNotContaining(original.fingerprint())
                    .hasMessageNotContaining("b".repeat(64));
            assertThat(guard.tryAcquire(original, RoleplayTurnDeadline.start())).isEmpty();
        }
    }

    @Test
    void releasedKeyCanBeReacquiredAndOldLeaseCannotReleaseNewOwner() {
        var session = UUID.randomUUID();
        var key = UUID.randomUUID();
        var oldLease =
                guard.tryAcquire(
                                identity(session, key, "a".repeat(64)),
                                RoleplayTurnDeadline.start())
                        .orElseThrow();
        oldLease.close();

        var next = identity(session, key, "b".repeat(64));
        try (var newLease = guard.tryAcquire(next, RoleplayTurnDeadline.start()).orElseThrow()) {
            oldLease.close();
            assertThat(guard.tryAcquire(next, RoleplayTurnDeadline.start())).isEmpty();
        }
        try (var reacquired = guard.tryAcquire(next, RoleplayTurnDeadline.start()).orElseThrow()) {
            assertThat(reacquired).isNotNull();
        }
    }

    @Test
    void sameKeyInDifferentSessionsHasIndependentOwnership() {
        var key = UUID.randomUUID();
        var first = identity(UUID.randomUUID(), key, "a".repeat(64));
        var other = identity(UUID.randomUUID(), key, "b".repeat(64));
        try (var firstLease = guard.tryAcquire(first, RoleplayTurnDeadline.start()).orElseThrow();
                var otherLease =
                        guard.tryAcquire(other, RoleplayTurnDeadline.start()).orElseThrow()) {
            assertThat(firstLease).isNotSameAs(otherLease);
            assertThat(guard.tryAcquire(first, RoleplayTurnDeadline.start())).isEmpty();
            assertThat(guard.tryAcquire(other, RoleplayTurnDeadline.start())).isEmpty();
        }
    }

    private RoleplayRequestIdentity identity(UUID session, UUID key, String fingerprint) {
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        session,
                        UUID.randomUUID(),
                        null,
                        null);
        return new RoleplayRequestIdentity(trace, key, fingerprint, 0, 1);
    }
}
