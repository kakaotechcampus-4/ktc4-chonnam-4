package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.roleplay.domain.RoleplayTurn;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.roleplay.repository.RoleplayTurnRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Caller supplies identity from authenticated server context, never from request body fields. */
@Service
public class JpaRoleplayCheckpointStore implements RoleplayCheckpointStore {
    private final ChildRepository children;
    private final ActivityRepository activities;
    private final RoleplaySessionRepository sessions;
    private final RoleplayTurnRepository turns;
    private final PlatformTransactionManager manager;
    private final JdbcTemplate jdbc;

    public JpaRoleplayCheckpointStore(
            ChildRepository children,
            ActivityRepository activities,
            RoleplaySessionRepository sessions,
            RoleplayTurnRepository turns,
            PlatformTransactionManager manager,
            JdbcTemplate jdbc) {
        this.children = children;
        this.activities = activities;
        this.sessions = sessions;
        this.turns = turns;
        this.manager = manager;
        this.jdbc = jdbc;
    }

    @Override
    public LookupResult findConfirmed(
            RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        var trace = identity.trace();
        var session = sessions.findOwned(trace.sessionId(), trace.childId(), trace.activityId());
        deadline.requireActive();
        if (session.isEmpty()
                || !session.get().matchesScenario(trace.scenarioId(), trace.scenarioVersion()))
            return new Conflict();
        var previous =
                turns.findBySessionIdAndIdempotencyKey(
                        trace.sessionId(), identity.idempotencyKey());
        deadline.requireActive();
        if (previous.isPresent()) {
            var saved = previous.get();
            if (!saved.getInputFingerprint().equals(identity.fingerprint())) return new Conflict();
            return new Found(
                    new Committed(
                            saved.getTurnId(),
                            saved.getCandidateId(),
                            saved.getCheckpointVersion(),
                            true,
                            saved.getResponseText()));
        }
        return session.get().canAdvance(identity.expectedVersion(), identity.turnNumber())
                ? new Absent()
                : new Conflict();
    }

    @Override
    public CommitResult commit(RoleplayCheckpointCommand command, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(deadline);
        try {
            deadline.requireActive();
            var transaction = new TransactionTemplate(manager);
            transaction.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return transaction.execute(
                    status -> {
                        deadline.requireActive();
                        long millis = Math.max(1, (deadline.remainingNanos() + 999999) / 1000000);
                        jdbc.queryForObject(
                                "select set_config('lock_timeout', ?, true)",
                                String.class,
                                millis + "ms");
                        jdbc.queryForObject(
                                "select set_config('statement_timeout', ?, true)",
                                String.class,
                                millis + "ms");
                        // Match deletion/quiz: activity -> child -> session, only during commit.
                        var activity = activities.findByIdForUpdate(command.activityId());
                        deadline.requireActive();
                        var child = children.findByIdForUpdate(command.childId());
                        deadline.requireActive();
                        var session =
                                sessions.findOwnedForUpdate(
                                        command.sessionId(),
                                        command.childId(),
                                        command.activityId());
                        deadline.requireActive();
                        if (session.isEmpty()
                                || !session.get()
                                        .matchesScenario(
                                                command.scenarioId(), command.scenarioVersion()))
                            return new Conflict();
                        var previous =
                                turns.findBySessionIdAndIdempotencyKey(
                                        command.sessionId(), command.idempotencyKey());
                        if (previous.isPresent()) {
                            var saved = previous.get();
                            if (!saved.getInputFingerprint().equals(command.inputFingerprint()))
                                return new Conflict();
                            return new Committed(
                                    saved.getTurnId(),
                                    saved.getCandidateId(),
                                    saved.getCheckpointVersion(),
                                    true,
                                    saved.getResponseText());
                        }
                        if (child.isEmpty()
                                || child.get().getStatus() != ChildStatus.ACTIVE
                                || activity.isEmpty()
                                || activity.get().getStatus() != ActivityStatus.IN_PROGRESS
                                || !activity.get().getChildId().equals(command.childId())
                                || !command.scenarioId().equals(activity.get().getScenarioId()))
                            return new Conflict();
                        if (!session.get()
                                        .canAdvance(command.expectedVersion(), command.turnNumber())
                                || turns.existsById(command.turnId())) return new Conflict();
                        deadline.requireActive();
                        Instant now = Instant.now();
                        long version = session.get().getRowVersion() + 1;
                        turns.saveAndFlush(new RoleplayTurn(command, version, now));
                        session.get()
                                .advance(
                                        command.turnId(),
                                        command.turnNumber(),
                                        command.microGoalId(),
                                        command.supportLevel(),
                                        now);
                        sessions.flush();
                        deadline.requireActive(); // Expiry here rolls back both writes.
                        return new Committed(
                                command.turnId(),
                                command.candidateId(),
                                version,
                                false,
                                command.responseText());
                    });
        } catch (RoleplayTurnDeadline.Expired expired) {
            return new Expired();
        } catch (RuntimeException failure) {
            if (deadline.remainingNanos() == 0) return new Expired();
            throw failure;
        }
    }
}
