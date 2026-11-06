package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.roleplay.repository.RoleplayTurnRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DEC-039: no report-success dependency or copied canonical text in replay caches. */
@Service
public class RoleplayCanonicalRetentionService {
    private static final Duration INCOMPLETE_RETENTION = Duration.ofHours(24);
    private final RoleplaySessionRepository sessions;
    private final RoleplayTurnRepository turns;
    private final RoleplayVoiceFailureStore failures;
    private final Clock clock;
    private final RoleplayRetentionGate retentionGate;

    public RoleplayCanonicalRetentionService(
            RoleplaySessionRepository sessions,
            RoleplayTurnRepository turns,
            RoleplayVoiceFailureStore failures,
            RoleplayRetentionGate retentionGate,
            Clock clock) {
        this.sessions = sessions;
        this.turns = turns;
        this.failures = failures;
        this.retentionGate = retentionGate;
        this.clock = clock;
    }

    /** Normal completion only; joins the caller's activity/reward/record transaction if present. */
    @Transactional
    public int completeNormallyAndDelete(
            UUID sessionId, UUID authenticatedChildId, UUID activityId) {
        var session =
                sessions.findOwnedForUpdate(sessionId, authenticatedChildId, activityId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Owned roleplay session not found"));
        session.completeNormally(clock.instant());
        int erased = turns.deleteCanonicalUtterances(sessionId);
        failures.deleteBySessionId(sessionId);
        sessions.flush();
        return erased;
    }

    @Transactional
    public int purgeExpiredCanonicalUtterances() {
        Instant cutoff = clock.instant().minus(INCOMPLETE_RETENTION);
        int erased = 0;
        for (var session : sessions.findRetainingExpiredForUpdate(cutoff)) {
            // Recheck locked state; concurrent checkpoint writes acquire the same session lock.
            if (session.requiresCanonicalCleanup(cutoff)) {
                var lease = retentionGate.tryEnterCleanup(session.getSessionId());
                if (lease.isEmpty()) continue;
                try {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(
                                    new org.springframework.transaction.support
                                            .TransactionSynchronization() {
                                        @Override
                                        public void afterCompletion(int status) {
                                            lease.get().close();
                                        }
                                    });
                } catch (RuntimeException failure) {
                    lease.get().close();
                    throw failure;
                }
                erased += turns.deleteCanonicalUtterances(session.getSessionId());
                failures.deleteBySessionId(session.getSessionId());
            }
        }
        return erased;
    }
}
