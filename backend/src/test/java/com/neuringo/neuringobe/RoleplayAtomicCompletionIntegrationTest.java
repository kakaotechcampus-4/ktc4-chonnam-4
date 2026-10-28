package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.roleplay.service.JpaRoleplayCheckpointStore;
import com.neuringo.neuringobe.roleplay.service.RoleplayCanonicalRetentionService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 완료 서비스 연결 전, 마지막 턴과 완료·보상·기록이 함께 저장되거나 취소되는지 확인한다.
 *
 * <p>목표 달성 여부와 완료·보상·기록 저장은 테스트용으로 만들고, 세션 종료와 발화 삭제는 실제 서비스를 사용한다.
 */
@IntegrationTest
@TestPropertySource(properties = "roleplay.retention.cleanup-enabled=false")
class RoleplayAtomicCompletionIntegrationTest {
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-27T00:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired JpaRoleplayCheckpointStore store;
    @Autowired RoleplayCanonicalRetentionService retention;
    @Autowired PlatformTransactionManager transactions;
    private final AtomicInteger completionCalls = new AtomicInteger();

    @BeforeEach
    void prepareFixtureRecordTable() {
        jdbc.execute(
                """
                create table if not exists test_roleplay_completion_record (
                    activity_id uuid primary key references activity(activity_id) on delete cascade,
                    record_id uuid not null unique,
                    write_allowed boolean not null check (write_allowed)
                )
                """);
    }

    @Test
    void recordFailureRollsBackLastTurnCompletionRewardAndDeletionThenRetrySucceeds() {
        var f = fixture();
        var command = command(f, UUID.randomUUID(), "a".repeat(64), 1, 2);

        assertThatThrownBy(
                        () ->
                                saveWithCompletionFixture(
                                        f,
                                        command,
                                        true,
                                        false,
                                        RoleplayTurnDeadline.start(),
                                        false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertUnchanged(f);

        var saved =
                (RoleplayCheckpointStore.Committed)
                        saveWithCompletionFixture(
                                f, command, true, true, RoleplayTurnDeadline.start(), false);
        assertThat(saved.replayed()).isFalse();
        assertCompleted(f);
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.session))
                .isEqualTo(saved.turnId());
    }

    @Test
    void replayAndChangedInputDoNotRepeatCompletionOrChangeItsResult() {
        var f = fixture();
        UUID key = UUID.randomUUID();
        var first =
                (RoleplayCheckpointStore.Committed)
                        saveWithCompletionFixture(
                                f,
                                command(f, key, "a".repeat(64), 1, 2),
                                true,
                                true,
                                RoleplayTurnDeadline.start(),
                                false);
        var original = completionSnapshot(f);
        var replay =
                (RoleplayCheckpointStore.Committed)
                        saveWithCompletionFixture(
                                f,
                                command(f, key, "a".repeat(64), 1, 2),
                                true,
                                true,
                                RoleplayTurnDeadline.start(),
                                false);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.candidateId()).isEqualTo(first.candidateId());
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(
                        saveWithCompletionFixture(
                                f,
                                command(f, key, "b".repeat(64), 1, 2),
                                true,
                                true,
                                RoleplayTurnDeadline.start(),
                                false))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(completionCalls).hasValue(1);
        assertThat(completionSnapshot(f)).isEqualTo(original);
        assertCompleted(f);
    }

    @Test
    void unachievedActivityGoalStoresTheTurnWithoutCompletionRewardOrDeletion() {
        var f = fixture();
        assertThat(
                        saveWithCompletionFixture(
                                f,
                                command(f, UUID.randomUUID(), "a".repeat(64), 1, 2),
                                false,
                                true,
                                RoleplayTurnDeadline.start(),
                                false))
                .isInstanceOf(RoleplayCheckpointStore.Committed.class);
        assertThat(turns(f)).isEqualTo(2);
        assertThat(canonicalTurns(f)).isEqualTo(2);
        assertThat(records(f)).isZero();
        assertThat(completionCalls).hasValue(0);
        assertThat(activity(f)).containsEntry("status", "IN_PROGRESS");
        assertThat(activity(f).get("completed_at")).isNull();
        assertThat(activity(f).get("reward_issued_at")).isNull();
    }

    @Test
    void cancellationBeforeOuterCommitRollsBackTheWholeCompletion() {
        var f = fixture();
        assertThatThrownBy(
                        () ->
                                saveWithCompletionFixture(
                                        f,
                                        command(f, UUID.randomUUID(), "a".repeat(64), 1, 2),
                                        true,
                                        true,
                                        RoleplayTurnDeadline.start(),
                                        true))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertUnchanged(f);
    }

    @Test
    void concurrentSameKeyCommitsOneFinalTurnAndOneCompletion() throws Exception {
        var f = fixture();
        UUID key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first =
                    callers.submit(
                            () -> {
                                start.await();
                                return saveWithCompletionFixture(
                                        f,
                                        command(f, key, "a".repeat(64), 1, 2),
                                        true,
                                        true,
                                        RoleplayTurnDeadline.start(),
                                        false);
                            });
            var second =
                    callers.submit(
                            () -> {
                                start.await();
                                return saveWithCompletionFixture(
                                        f,
                                        command(f, key, "a".repeat(64), 1, 2),
                                        true,
                                        true,
                                        RoleplayTurnDeadline.start(),
                                        false);
                            });
            start.countDown();
            var results =
                    List.of(
                            (RoleplayCheckpointStore.Committed) first.get(10, TimeUnit.SECONDS),
                            (RoleplayCheckpointStore.Committed) second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RoleplayCheckpointStore.Committed::replayed).count())
                    .isEqualTo(1);
            assertThat(
                            results.stream()
                                    .map(RoleplayCheckpointStore.Committed::candidateId)
                                    .distinct())
                    .hasSize(1);
        }
        assertThat(completionCalls).hasValue(1);
        assertCompleted(f);
    }

    @Test
    void participatingCommitRejectsAMissingOuterTransactionWithoutWriting() {
        var f = fixture();
        assertThatThrownBy(
                        () ->
                                store.commitInCurrentTransaction(
                                        command(f, UUID.randomUUID(), "a".repeat(64), 1, 2),
                                        RoleplayTurnDeadline.start()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertUnchanged(f);
    }

    @Test
    void standaloneCommitKeepsItsIndependentTransactionBehavior() {
        var f = fixture();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                ignored -> {
                                                    assertThat(
                                                                    store.commit(
                                                                            command(
                                                                                    f,
                                                                                    UUID
                                                                                            .randomUUID(),
                                                                                    "a".repeat(64),
                                                                                    1,
                                                                                    2),
                                                                            RoleplayTurnDeadline
                                                                                    .start()))
                                                            .isInstanceOf(
                                                                    RoleplayCheckpointStore
                                                                            .Committed.class);
                                                    throw new IllegalStateException(
                                                            "synthetic outer failure");
                                                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic outer failure");
        assertThat(turns(f)).isEqualTo(2);
        assertThat(canonicalTurns(f)).isEqualTo(2);
        assertThat(records(f)).isZero();
        assertThat(activity(f)).containsEntry("status", "IN_PROGRESS");
    }

    private RoleplayCheckpointStore.CommitResult saveWithCompletionFixture(
            Fixture f,
            RoleplayCheckpointCommand command,
            boolean activityGoalAchieved,
            boolean recordWriteAllowed,
            RoleplayTurnDeadline deadline,
            boolean cancelBeforeCommit) {
        return new TransactionTemplate(transactions)
                .execute(
                        ignored -> {
                            var result = store.commitInCurrentTransaction(command, deadline);
                            if (result instanceof RoleplayCheckpointStore.Committed saved
                                    && !saved.replayed()
                                    && activityGoalAchieved) {
                                completionCalls.incrementAndGet();
                                jdbc.update(
                                        "update activity set status='COMPLETED', completed_at=?, reward_issued_at=? where activity_id=?",
                                        Timestamp.from(COMPLETED_AT),
                                        Timestamp.from(COMPLETED_AT),
                                        f.activity);
                                retention.completeNormallyAndDelete(f.session, f.child, f.activity);
                                jdbc.update(
                                        "insert into test_roleplay_completion_record(activity_id, record_id, write_allowed) values(?,?,?)",
                                        f.activity,
                                        UUID.randomUUID(),
                                        recordWriteAllowed);
                            }
                            if (cancelBeforeCommit) deadline.cancel();
                            deadline.requireActive();
                            return result;
                        });
    }

    private void assertUnchanged(Fixture f) {
        assertThat(turns(f)).isEqualTo(1);
        assertThat(canonicalTurns(f)).isEqualTo(1);
        assertThat(records(f)).isZero();
        assertThat(activity(f)).containsEntry("status", "IN_PROGRESS");
        assertThat(activity(f).get("completed_at")).isNull();
        assertThat(activity(f).get("reward_issued_at")).isNull();
        assertThat(
                        jdbc.queryForMap(
                                "select status, last_turn_number, row_version from roleplay_session where session_id=?",
                                f.session))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("last_turn_number", 1)
                .containsEntry("row_version", 1L);
    }

    private void assertCompleted(Fixture f) {
        assertThat(turns(f)).isEqualTo(2);
        assertThat(canonicalTurns(f)).isZero();
        assertThat(records(f)).isEqualTo(1);
        assertThat(activity(f))
                .containsEntry("status", "COMPLETED")
                .containsEntry("completed_at", Timestamp.from(COMPLETED_AT))
                .containsEntry("reward_issued_at", Timestamp.from(COMPLETED_AT));
        assertThat(
                        jdbc.queryForObject(
                                "select status from roleplay_session where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("COMPLETED");
    }

    private java.util.Map<String, Object> activity(Fixture f) {
        return jdbc.queryForMap(
                "select status, completed_at, reward_issued_at from activity where activity_id=?",
                f.activity);
    }

    private java.util.Map<String, Object> completionSnapshot(Fixture f) {
        return jdbc.queryForMap(
                "select a.completed_at, a.reward_issued_at, r.record_id, s.row_version from activity a join test_roleplay_completion_record r on r.activity_id=a.activity_id join roleplay_session s on s.activity_id=a.activity_id where s.session_id=?",
                f.session);
    }

    private int turns(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=?", Integer.class, f.session);
    }

    private int canonicalTurns(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=? and canonical_utterance is not null",
                Integer.class,
                f.session);
    }

    private int records(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from test_roleplay_completion_record where activity_id=?",
                Integer.class,
                f.activity);
    }

    private RoleplayCheckpointCommand command(
            Fixture f, UUID key, String fingerprint, long version, int number) {
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        f.activity,
                        null,
                        f.child,
                        f.scenario,
                        1,
                        f.session,
                        UUID.randomUUID(),
                        null,
                        null);
        UUID goal = UUID.randomUUID(), candidate = UUID.randomUUID();
        var analysis =
                new AnalysisResult(
                        trace.turnId(),
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        "GOAL_ACHIEVED",
                        new AnalysisResult.PrimaryGap("NONE", null),
                        new AnalysisResult.NextStrategy("CLOSE_ROLEPLAY", goal, null, "S1"),
                        0.9);
        var response =
                new CandidateResponse(
                        candidate,
                        trace.turnId(),
                        "synthetic approved response",
                        "CLOSING",
                        "CLOSE_ROLEPLAY",
                        goal,
                        "S1",
                        List.of());
        var evaluation =
                new EvaluationResult(
                        candidate, true, EvaluationDecision.PASS, null, 0, List.of(), null);
        var spoken =
                new RoleplayTurnResult.SpokenReady(
                        new RoleplayTurnResult.Ready(analysis, response, evaluation),
                        "synthetic canonical input",
                        null);
        return RoleplayCheckpointCommand.fromApproved(
                trace, version, number, key, fingerprint, spoken);
    }

    private Fixture fixture() {
        UUID user = UUID.randomUUID(), classroom = UUID.randomUUID(), child = UUID.randomUUID();
        UUID goal = UUID.randomUUID(), activity = UUID.randomUUID(), session = UUID.randomUUID();
        var f = new Fixture(session, activity, child, UUID.randomUUID());
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)",
                user,
                user + "@example.com",
                "test-hash",
                now);
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,?)",
                goal,
                child,
                user,
                "a".repeat(64),
                now);
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,assigned_at) values(?,?,?,?,'ROLEPLAY','IN_PROGRESS',?)",
                activity,
                child,
                goal,
                f.scenario,
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                session,
                activity,
                child,
                f.scenario,
                now);
        store.commit(
                command(f, UUID.randomUUID(), "c".repeat(64), 0, 1), RoleplayTurnDeadline.start());
        return f;
    }

    private record Fixture(UUID session, UUID activity, UUID child, UUID scenario) {}
}
