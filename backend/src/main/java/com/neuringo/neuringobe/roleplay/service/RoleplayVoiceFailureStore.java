package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceFailureProgress;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceFailureProgress.CountingStatus;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.roleplay.repository.RoleplayTurnRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 음성 실패 결과와 누적 횟수를 함께 확정한다. 학습 턴·관찰·지원 상태는 변경하지 않는다. */
@Service
public class RoleplayVoiceFailureStore {
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final RoleplaySessionRepository sessions;
    private final RoleplayTurnRepository turns;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RoleplayVoiceFailureStore(
            ActivityRepository activities,
            ChildRepository children,
            RoleplaySessionRepository sessions,
            RoleplayTurnRepository turns,
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock) {
        this.activities = activities;
        this.children = children;
        this.sessions = sessions;
        this.turns = turns;
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Result findRecorded(RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(identity);
        return inDeadline(
                deadline,
                () -> {
                    var trace = identity.trace();
                    var session =
                            sessions.findOwned(
                                    trace.sessionId(), trace.childId(), trace.activityId());
                    if (session.isEmpty()
                            || !session.get()
                                    .matchesScenario(trace.scenarioId(), trace.scenarioVersion())) {
                        return new Conflict();
                    }
                    var recorded = findPrevious(identity);
                    if (recorded != null) return recorded;
                    if (turns.findBySessionIdAndIdempotencyKey(
                                    trace.sessionId(), identity.idempotencyKey())
                            .isPresent()) {
                        return new Conflict();
                    }
                    return session.get()
                                    .canAdvance(identity.expectedVersion(), identity.turnNumber())
                            ? new Absent()
                            : new Conflict();
                });
    }

    public Result recordFailure(
            RoleplayRequestIdentity identity, InputFailure reason, RoleplayTurnDeadline deadline) {
        return recordFailure(identity, new RoleplayTurnResult.InputRejected(reason), deadline);
    }

    public Result recordFailure(
            RoleplayRequestIdentity identity,
            RoleplayTurnResult.InputRejected rejected,
            RoleplayTurnDeadline deadline) {
        return recordFailure(identity, rejected, deadline, false);
    }

    public Result recordUnidentifiedUpload(
            RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        return recordFailure(
                identity,
                new RoleplayTurnResult.InputRejected(InputFailure.UPLOAD_TOO_LARGE),
                deadline,
                true);
    }

    private Result recordFailure(
            RoleplayRequestIdentity identity,
            RoleplayTurnResult.InputRejected rejected,
            RoleplayTurnDeadline deadline,
            boolean unidentified) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(rejected);
        var reason = rejected.reason();
        var evidence = rejected.confidenceEvidence();
        return inDeadline(
                deadline,
                () -> {
                    var trace = identity.trace();
                    // 정상 턴·완료·삭제와 같은 잠금 순서를 지킨다.
                    var activity = activities.findByIdForUpdate(trace.activityId());
                    var child = children.findByIdForUpdate(trace.childId());
                    var session =
                            sessions.findOwnedForUpdate(
                                    trace.sessionId(), trace.childId(), trace.activityId());
                    deadline.requireActive();
                    if (session.isEmpty()
                            || !session.get()
                                    .matchesScenario(trace.scenarioId(), trace.scenarioVersion())) {
                        return new Conflict();
                    }
                    var previous = findPrevious(identity);
                    if (previous != null) return unidentified ? new Conflict() : previous;
                    if (turns.findBySessionIdAndIdempotencyKey(
                                    trace.sessionId(), identity.idempotencyKey())
                            .isPresent()) {
                        return new Conflict();
                    }
                    if (activity.isEmpty()
                            || child.isEmpty()
                            || child.get().getStatus() != ChildStatus.ACTIVE
                            || activity.get().getStatus() != ActivityStatus.IN_PROGRESS
                            || !activity.get().getChildId().equals(trace.childId())
                            || !trace.scenarioId().equals(activity.get().getScenarioId())
                            || !session.get()
                                    .canAdvance(
                                            identity.expectedVersion(), identity.turnNumber())) {
                        return new Conflict();
                    }
                    var counts =
                            jdbc.queryForList(
                                    "select failure_count from roleplay_voice_input_state where session_id=?",
                                    Integer.class,
                                    trace.sessionId());
                    var progress =
                            new RoleplayVoiceFailureProgress(
                                    counts.isEmpty() ? 0 : counts.getFirst());
                    var update = progress.recordNewFailure(reason);
                    if (update.status() == CountingStatus.POLICY_REQUIRED)
                        return new PolicyRequired(progress.textRecommended());
                    var recordedAt = Timestamp.from(clock.instant());
                    jdbc.update(
                            """
                    insert into roleplay_voice_input_state(session_id, failure_count) values (?, ?)
                    on conflict (session_id) do update set failure_count=excluded.failure_count
                    """,
                            trace.sessionId(),
                            update.progress().failureCount());
                    jdbc.update(
                            """
                    insert into roleplay_voice_input_failure
                        (session_id, idempotency_key, input_fingerprint, checkpoint_version,
                         failure_reason, failure_count_after, recorded_at,
                         stt_confidence, stt_confidence_threshold)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                            trace.sessionId(),
                            identity.idempotencyKey(),
                            unidentified ? null : identity.fingerprint(),
                            identity.expectedVersion(),
                            reason.name(),
                            update.progress().failureCount(),
                            recordedAt,
                            evidence == null ? null : evidence.confidence(),
                            evidence == null ? null : evidence.threshold());
                    jdbc.update(
                            "update roleplay_session set last_activity_at=greatest(last_activity_at, ?) where session_id=?",
                            recordedAt,
                            trace.sessionId());
                    return new Recorded(reason, update.progress(), false, update.progress());
                });
    }

    private Result findPrevious(RoleplayRequestIdentity identity) {
        var rows =
                jdbc.query(
                        """
                select f.input_fingerprint, f.failure_reason, f.failure_count_after, s.failure_count
                from roleplay_voice_input_failure f
                join roleplay_voice_input_state s on s.session_id=f.session_id
                where f.session_id=? and f.idempotency_key=?
                """,
                        (row, index) ->
                                new StoredFailure(
                                        row.getString("input_fingerprint"),
                                        InputFailure.valueOf(row.getString("failure_reason")),
                                        row.getInt("failure_count_after"),
                                        row.getInt("failure_count")),
                        identity.trace().sessionId(),
                        identity.idempotencyKey());
        if (rows.isEmpty()) return null;
        var stored = rows.getFirst();
        if (stored.fingerprint() == null || !stored.fingerprint().equals(identity.fingerprint()))
            return new Conflict();
        return new Recorded(
                stored.reason(),
                new RoleplayVoiceFailureProgress(stored.count()),
                true,
                new RoleplayVoiceFailureProgress(stored.currentCount()));
    }

    private Result inDeadline(RoleplayTurnDeadline deadline, Supplier<Result> operation) {
        Objects.requireNonNull(deadline);
        try {
            deadline.requireActive();
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
                        var result = operation.get();
                        deadline.requireActive();
                        return result;
                    });
        } catch (RoleplayTurnDeadline.Expired expired) {
            return new Expired();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteBySessionId(UUID sessionId) {
        jdbc.update("delete from roleplay_voice_input_failure where session_id=?", sessionId);
        jdbc.update("delete from roleplay_voice_input_state where session_id=?", sessionId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteByChildIds(List<UUID> childIds) {
        if (childIds.isEmpty()) return;
        var parameters = Map.of("children", childIds);
        for (var table : List.of("roleplay_voice_input_failure", "roleplay_voice_input_state")) {
            named.update(
                    "delete from "
                            + table
                            + " where session_id in (select session_id from roleplay_session where child_id in (:children))",
                    parameters);
        }
    }

    public sealed interface Result permits Recorded, Absent, Conflict, PolicyRequired, Expired {}

    /** 재전송에는 당시 확정 횟수를 돌려주며 현재 누적 상태를 되돌리지 않는다. */
    public record Recorded(
            InputFailure reason,
            RoleplayVoiceFailureProgress progress,
            boolean replayed,
            RoleplayVoiceFailureProgress currentProgress)
            implements Result {}

    public record Absent() implements Result {}

    public record Conflict() implements Result {}

    public record PolicyRequired(boolean textRecommended) implements Result {}

    public record Expired() implements Result {}

    private record StoredFailure(
            String fingerprint, InputFailure reason, int count, int currentCount) {}
}
