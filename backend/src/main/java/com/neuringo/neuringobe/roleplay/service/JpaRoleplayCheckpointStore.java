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

/** 아동·활동 식별자는 요청 본문이 아닌, 서버가 인증·소유권을 확인한 값으로 전달받는다. */
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
        if (hasRecordedVoiceFailure(trace.sessionId(), identity.idempotencyKey()))
            return new Conflict();
        return session.get().canAdvance(identity.expectedVersion(), identity.turnNumber())
                ? new Absent()
                : new Conflict();
    }

    private boolean hasRecordedVoiceFailure(java.util.UUID sessionId, java.util.UUID key) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from roleplay_voice_input_failure where session_id=? and idempotency_key=?)",
                        Boolean.class,
                        sessionId,
                        key));
    }

    @Override
    public CommitResult commit(RoleplayCheckpointCommand command, RoleplayTurnDeadline deadline) {
        return commit(
                command,
                deadline,
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 완료 서비스가 시작한 트랜잭션에서 마지막 턴을 저장한다. 트랜잭션이 없으면 오류를 낸다.
     *
     * <p>스탬프 지급이나 기록 저장이 실패하면 마지막 턴도 함께 취소된다. 전체 저장과 기한 검사가 끝나고 트랜잭션이 확정된 뒤에만 성공을 응답한다.
     *
     * <p>외부 호출은 트랜잭션 시작 전에 마친다. 재전송된 요청에는 완료·보상을 다시 적용하지 않는다.
     */
    public CommitResult commitInCurrentTransaction(
            RoleplayCheckpointCommand command, RoleplayTurnDeadline deadline) {
        return commit(
                command,
                deadline,
                org.springframework.transaction.TransactionDefinition.PROPAGATION_MANDATORY);
    }

    private CommitResult commit(
            RoleplayCheckpointCommand command, RoleplayTurnDeadline deadline, int propagation) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(deadline);
        try {
            deadline.requireActive();
            var transaction = new TransactionTemplate(manager);
            transaction.setPropagationBehavior(propagation);
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
                        // 삭제·퀴즈 저장과 같은 순서로 활동, 아동, 세션을 잠가 교착을 피한다.
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
                        if (hasRecordedVoiceFailure(command.sessionId(), command.idempotencyKey()))
                            return new Conflict();
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
                        deadline.requireActive(); // 기한을 넘겼다면 턴 저장과 세션 갱신을 함께 취소한다.
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
