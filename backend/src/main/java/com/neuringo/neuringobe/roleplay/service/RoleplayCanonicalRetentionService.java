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
    private final Clock clock;

    public RoleplayCanonicalRetentionService(
            RoleplaySessionRepository sessions, RoleplayTurnRepository turns, Clock clock) {
        this.sessions = sessions;
        this.turns = turns;
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
                erased += turns.deleteCanonicalUtterances(session.getSessionId());
            }
        }
        return erased;
    }
}
