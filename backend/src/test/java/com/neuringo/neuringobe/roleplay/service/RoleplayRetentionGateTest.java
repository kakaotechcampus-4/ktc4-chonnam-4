package com.neuringo.neuringobe.roleplay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RoleplayRetentionGateTest {
    private final RoleplayRetentionGate gate = new RoleplayRetentionGate();

    @Test
    void activeInputDefersOnlyItsOwnSessionAndMultipleInputsRemainProtected() {
        var session = UUID.randomUUID();
        var other = UUID.randomUUID();
        try (var first = gate.enterInput(session, RoleplayTurnDeadline.start());
                var second = gate.enterInput(session, RoleplayTurnDeadline.start());
                var unrelated = gate.tryEnterCleanup(other).orElseThrow()) {
            assertThat(gate.tryEnterCleanup(session)).isEmpty();
            first.close();
            assertThat(gate.tryEnterCleanup(session)).isEmpty();
        }
        try (var cleanup = gate.tryEnterCleanup(session).orElseThrow()) {
            assertThat(cleanup).isNotNull();
        }
    }

    @Test
    void inputWaitingForCleanupUsesItsExistingDeadline() throws Exception {
        var session = UUID.randomUUID();
        try (var cleanup = gate.tryEnterCleanup(session).orElseThrow();
                var workers = Executors.newSingleThreadExecutor()) {
            var outcome =
                    workers.submit(
                            () -> {
                                assertThatThrownBy(
                                                () ->
                                                        gate.enterInput(
                                                                session,
                                                                RoleplayTurnDeadline.start(
                                                                        Duration.ofMillis(100))))
                                        .isInstanceOf(RoleplayTurnDeadline.Expired.class);
                                return true;
                            });
            assertThat(outcome.get(2, TimeUnit.SECONDS)).isTrue();
        }
        try (var input = gate.enterInput(session, RoleplayTurnDeadline.start())) {
            assertThat(input).isNotNull();
        }
    }

    @Test
    void expiredInputDoesNotBlockCleanupWhileItsRequestIsStillOpen() {
        var session = UUID.randomUUID();
        var deadline = RoleplayTurnDeadline.start();
        try (var input = gate.enterInput(session, deadline)) {
            assertThat(gate.tryEnterCleanup(session)).isEmpty();
            deadline.cancel();
            try (var cleanup = gate.tryEnterCleanup(session).orElseThrow()) {
                assertThat(cleanup).isNotNull();
            }
        }
        try (var next = gate.enterInput(session, RoleplayTurnDeadline.start())) {
            assertThat(gate.tryEnterCleanup(session)).isEmpty();
        }
    }

    @Test
    void expiredInputDoesNotRemoveTheProtectionOfAnotherLiveInput() {
        var session = UUID.randomUUID();
        var expired = RoleplayTurnDeadline.start();
        try (var first = gate.enterInput(session, expired);
                var second = gate.enterInput(session, RoleplayTurnDeadline.start())) {
            expired.cancel();
            assertThat(gate.tryEnterCleanup(session)).isEmpty();
        }
        try (var cleanup = gate.tryEnterCleanup(session).orElseThrow()) {
            assertThat(cleanup).isNotNull();
        }
    }
}
