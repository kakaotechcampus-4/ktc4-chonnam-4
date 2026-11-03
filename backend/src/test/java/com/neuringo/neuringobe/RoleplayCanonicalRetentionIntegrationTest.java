package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestGuard;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnRunner;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.roleplay.service.RoleplayCanonicalRetentionService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@TestPropertySource(properties = "roleplay.retention.cleanup-enabled=false")
class RoleplayCanonicalRetentionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    @MockitoBean Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired RoleplayCanonicalRetentionService retention;
    @Autowired com.neuringo.neuringobe.roleplay.service.RoleplayVoiceFailureStore failures;
    @Autowired com.neuringo.neuringobe.roleplay.service.RoleplayRetentionGate retentionGate;
    @Autowired RoleplayCheckpointStore store;
    @Autowired RoleplayRequestGuard guard;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void fixedClock() {
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void normalCompletionDeletesImmediatelyAndKeepsApprovedResponseAndCheckpoint() {
        var f = fixture();
        seedQuality(f);
        seedFailure(f);
        assertThat(retention.completeNormallyAndDelete(f.session, f.child, f.activity))
                .isEqualTo(1);
        assertThat(canonical(f)).isNull();
        assertThat(hasQuality(f)).isFalse();
        assertThat(failureStateCount(f)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status from roleplay_session where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select response_text from roleplay_turn where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("synthetic approved response");
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_number from roleplay_session where session_id=?",
                                Integer.class,
                                f.session))
                .isEqualTo(1);
        Long version =
                jdbc.queryForObject(
                        "select row_version from roleplay_session where session_id=?",
                        Long.class,
                        f.session);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(retention.completeNormallyAndDelete(f.session, f.child, f.activity)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select last_activity_at from roleplay_session where session_id=?",
                                Timestamp.class,
                                f.session))
                .isEqualTo(Timestamp.from(NOW));
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(version);
    }

    @Test
    void databasePreventsQualityFromOutlivingCanonicalUtterance() {
        var f = fixture();
        seedQuality(f);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update roleplay_turn set canonical_utterance=null where session_id=?",
                                        f.session))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(canonical(f)).isEqualTo("synthetic canonical input");
        assertThat(hasQuality(f)).isTrue();
        assertThat(retention.completeNormallyAndDelete(f.session, f.child, f.activity))
                .isEqualTo(1);
        assertThat(canonical(f)).isNull();
        assertThat(hasQuality(f)).isFalse();
    }

    @Test
    void parentCompletionTransactionRollsBackAndCanRetryWithoutPartialDeletion() {
        var f = fixture();
        seedQuality(f);
        var before =
                jdbc.queryForMap(
                        "select status, completed_at, reward_issued_at from activity where activity_id=?",
                        f.activity);
        var transaction = new TransactionTemplate(transactions);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        ignored -> {
                                            stageActivityCompletionFixture(f);
                                            assertThat(
                                                            retention.completeNormallyAndDelete(
                                                                    f.session, f.child, f.activity))
                                                    .isEqualTo(1);
                                            assertThat(canonical(f)).isNull();
                                            assertThat(hasQuality(f)).isFalse();
                                            throw new IllegalStateException(
                                                    "synthetic parent completion failure");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic parent completion failure");
        assertThat(
                        jdbc.queryForMap(
                                "select status, completed_at, reward_issued_at from activity where activity_id=?",
                                f.activity))
                .isEqualTo(before);
        assertThat(canonical(f)).isEqualTo("synthetic canonical input");
        assertThat(hasQuality(f)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select status from roleplay_session where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("IN_PROGRESS");
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(1L);

        transaction.executeWithoutResult(
                ignored -> {
                    stageActivityCompletionFixture(f);
                    assertThat(retention.completeNormallyAndDelete(f.session, f.child, f.activity))
                            .isEqualTo(1);
                });
        assertThat(canonical(f)).isNull();
        assertThat(hasQuality(f)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select status from activity where activity_id=?",
                                String.class,
                                f.activity))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select completed_at = ? and reward_issued_at = ? from activity where activity_id=?",
                                Boolean.class,
                                Timestamp.from(NOW),
                                Timestamp.from(NOW),
                                f.activity))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select status from roleplay_session where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(2L);
    }

    @Test
    void concurrentNormalCompletionsEraseOnceAndAdvanceSessionVersionOnce() throws Exception {
        var f = fixture();
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first =
                    threads.submit(
                            () -> {
                                start.await();
                                return retention.completeNormallyAndDelete(
                                        f.session, f.child, f.activity);
                            });
            var other =
                    threads.submit(
                            () -> {
                                start.await();
                                return retention.completeNormallyAndDelete(
                                        f.session, f.child, f.activity);
                            });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), other.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 0);
        }
        assertThat(canonical(f)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_number from roleplay_session where session_id=?",
                                Integer.class,
                                f.session))
                .isEqualTo(1);
    }

    @Test
    void expiredCleanupIncludesExactlyTwentyFourHoursAndPausedSessionsButKeepsRecentActivity() {
        var exact = fixture();
        var old = fixture();
        var recent = fixture();
        var paused = fixture();
        for (var f : List.of(exact, old, recent, paused)) seedQuality(f);
        activity(exact, NOW.minus(Duration.ofHours(24)), "IN_PROGRESS");
        activity(old, NOW.minus(Duration.ofHours(24)).minusSeconds(1), "IN_PROGRESS");
        activity(recent, NOW.minus(Duration.ofHours(24)).plusSeconds(1), "IN_PROGRESS");
        activity(paused, NOW.minus(Duration.ofHours(25)), "PAUSED");
        assertThat(retention.purgeExpiredCanonicalUtterances()).isEqualTo(3);
        assertThat(canonical(exact)).isNull();
        assertThat(canonical(old)).isNull();
        assertThat(canonical(paused)).isNull();
        assertThat(canonical(recent)).isEqualTo("synthetic canonical input");
        assertThat(hasQuality(exact)).isFalse();
        assertThat(hasQuality(old)).isFalse();
        assertThat(hasQuality(paused)).isFalse();
        assertThat(hasQuality(recent)).isTrue();
        assertThat(retention.purgeExpiredCanonicalUtterances()).isZero();
    }

    @Test
    void completedSessionIsCleanedEvenIfItsActivityTimestampIsRecent() {
        var f = fixture();
        activity(f, NOW, "COMPLETED");
        assertThat(retention.purgeExpiredCanonicalUtterances()).isEqualTo(1);
        assertThat(canonical(f)).isNull();
    }

    @Test
    void failureOnlySessionsAreDeletedAtTwentyFourHoursAndRecentFailuresRemain() {
        var expired = fixture();
        var recent = fixture();
        for (var f : List.of(expired, recent)) {
            seedFailure(f);
            jdbc.update(
                    "update roleplay_turn set canonical_utterance=null, input_quality=null where session_id=?",
                    f.session);
        }
        activity(expired, NOW.minus(Duration.ofHours(24)), "IN_PROGRESS");
        activity(recent, NOW.minus(Duration.ofHours(24)).plusSeconds(1), "IN_PROGRESS");
        retention.purgeExpiredCanonicalUtterances();
        assertThat(failureStateCount(expired)).isZero();
        assertThat(failureStateCount(recent)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_failure where session_id=?",
                                Long.class,
                                expired.session))
                .isZero();
    }

    @Test
    void newFailureUpdatesLastActivityWithoutChangingCheckpointAndReplayDoesNotExtendIt() {
        var f = fixture();
        activity(f, NOW.minus(Duration.ofHours(25)), "IN_PROGRESS");
        var version =
                jdbc.queryForObject(
                        "select row_version from roleplay_session where session_id=?",
                        Long.class,
                        f.session);
        var id =
                new com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity(
                        trace(f), UUID.randomUUID(), "b".repeat(64), version, 2);
        failures.recordFailure(
                id, RoleplayTurnResult.InputFailure.NO_SPEECH, RoleplayTurnDeadline.start());
        assertThat(
                        jdbc.queryForObject(
                                "select last_activity_at from roleplay_session where session_id=?",
                                Timestamp.class,
                                f.session))
                .isEqualTo(Timestamp.from(NOW));
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(version);
        when(clock.instant()).thenReturn(NOW.plusSeconds(20));
        failures.recordFailure(
                id, RoleplayTurnResult.InputFailure.NO_SPEECH, RoleplayTurnDeadline.start());
        assertThat(
                        jdbc.queryForObject(
                                "select last_activity_at from roleplay_session where session_id=?",
                                Timestamp.class,
                                f.session))
                .isEqualTo(Timestamp.from(NOW));
        retention.purgeExpiredCanonicalUtterances();
        assertThat(failureStateCount(f)).isEqualTo(1);
        assertThat(canonical(f)).isEqualTo("synthetic canonical input");
    }

    @Test
    void deletionFailureRollsBackCompletionStatusAndLeavesOriginalData() {
        var f = fixture();
        String constraint = "test_retention_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table roleplay_turn add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + f.session
                        + "'::uuid or canonical_utterance is not null)");
        try {
            assertThatThrownBy(
                            () ->
                                    retention.completeNormallyAndDelete(
                                            f.session, f.child, f.activity))
                    .isInstanceOf(RuntimeException.class);
            assertThat(canonical(f)).isEqualTo("synthetic canonical input");
            assertThat(
                            jdbc.queryForObject(
                                    "select status from roleplay_session where session_id=?",
                                    String.class,
                                    f.session))
                    .isEqualTo("IN_PROGRESS");
        } finally {
            jdbc.execute("alter table roleplay_turn drop constraint " + constraint);
        }
    }

    @Test
    void failureLedgerDeletionErrorRollsBackCompletionAndCanonicalDeletion() {
        var f = fixture();
        seedFailure(f);
        var table = "retention_guard_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "create table "
                        + table
                        + " (session_id uuid references roleplay_voice_input_state(session_id))");
        try {
            jdbc.update("insert into " + table + "(session_id) values (?)", f.session);
            assertThatThrownBy(
                            () ->
                                    retention.completeNormallyAndDelete(
                                            f.session, f.child, f.activity))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(canonical(f)).isEqualTo("synthetic canonical input");
            assertThat(failureStateCount(f)).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select status from roleplay_session where session_id=?",
                                    String.class,
                                    f.session))
                    .isEqualTo("IN_PROGRESS");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from roleplay_voice_input_failure where session_id=?",
                                    Long.class,
                                    f.session))
                    .isEqualTo(1);
        } finally {
            jdbc.execute("drop table " + table);
        }
    }

    @Test
    void anotherChildCannotCompleteOrDeleteSessionText() {
        var f = fixture();
        assertThatThrownBy(
                        () ->
                                retention.completeNormallyAndDelete(
                                        f.session, UUID.randomUUID(), f.activity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(canonical(f)).isEqualTo("synthetic canonical input");
    }

    @Test
    void replayAfterDeletionReturnsStoredCandidateWithoutCanonicalTextOrAnotherPipelineCall() {
        var f = fixture();
        retention.completeNormallyAndDelete(f.session, f.child, f.activity);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            guard);
            var result =
                    (RoleplayCheckpointedTurnExecutor.Success)
                            executor.execute(
                                    trace(f),
                                    0,
                                    1,
                                    f.key,
                                    "a".repeat(64),
                                    RoleplayTurnDeadline.start(),
                                    deadline -> {
                                        throw new AssertionError(
                                                "Deleted input must not be regenerated or re-cached");
                                    });
            assertThat(result.replayed()).isTrue();
            assertThat(result.outcome()).isInstanceOf(RoleplayTurnOutcome.ApprovedResponse.class);
            assertThat(((RoleplayTurnOutcome.ApprovedResponse) result.outcome()).text())
                    .isEqualTo("synthetic approved response");
            assertThat(result.outcome().toString()).doesNotContain("synthetic canonical input");
            assertThat(canonical(f)).isNull();
        }
    }

    @Test
    void cleanupSkipsLockedActivityUpdateAndDoesNotDeleteRecentlyRefreshedSession()
            throws Exception {
        var f = fixture();
        activity(f, NOW.minus(Duration.ofHours(25)), "IN_PROGRESS");
        try (var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var query =
                    lock.prepareStatement(
                            "update roleplay_session set last_activity_at=? where session_id=?")) {
                query.setTimestamp(1, Timestamp.from(NOW));
                query.setObject(2, f.session);
                query.executeUpdate();
            }
            assertThat(retention.purgeExpiredCanonicalUtterances()).isZero();
            lock.commit();
        }
        assertThat(retention.purgeExpiredCanonicalUtterances()).isZero();
        assertThat(canonical(f)).isEqualTo("synthetic canonical input");
    }

    @Test
    void cleanupHoldsGateUntilItsEnclosingTransactionCommits() throws Exception {
        var f = fixture();
        activity(f, NOW.minus(Duration.ofHours(25)), "IN_PROGRESS");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var cleanup =
                    workers.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        retention.purgeExpiredCanonicalUtterances();
                                                        entered.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    3,
                                                                    java.util.concurrent.TimeUnit
                                                                            .SECONDS))
                                                                throw new IllegalStateException();
                                                        } catch (InterruptedException ex) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(ex);
                                                        }
                                                        return true;
                                                    }));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(
                                () ->
                                        retentionGate.enterInput(
                                                f.session,
                                                RoleplayTurnDeadline.start(Duration.ofMillis(100))))
                        .isInstanceOf(RoleplayTurnDeadline.Expired.class);
            } finally {
                release.countDown();
            }
            assertThat(cleanup.get(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try (var input = retentionGate.enterInput(f.session, RoleplayTurnDeadline.start())) {
                assertThat(input).isNotNull();
            }
        }
    }

    @Test
    void completionDuringAiWorkPreventsLateCheckpointAndDoesNotRestoreDeletedText()
            throws Exception {
        var f = fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor();
                var callers = Executors.newSingleThreadExecutor()) {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            guard);
            var trace = trace(f);
            var pending =
                    callers.submit(
                            () ->
                                    executor.execute(
                                            trace,
                                            1,
                                            2,
                                            UUID.randomUUID(),
                                            "b".repeat(64),
                                            RoleplayTurnDeadline.start(),
                                            deadline -> {
                                                entered.countDown();
                                                try {
                                                    release.await();
                                                } catch (InterruptedException failure) {
                                                    throw new IllegalStateException(failure);
                                                }
                                                return spoken(trace);
                                            }));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                retention.completeNormallyAndDelete(f.session, f.child, f.activity);
                release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(RoleplayCheckpointedTurnExecutor.Conflict.class);
                assertThat(canonical(f)).isNull();
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from roleplay_turn where session_id=?",
                                        Integer.class,
                                        f.session))
                        .isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void concurrentCleanupJobsEraseOnceWithoutChangingCheckpointVersion() throws Exception {
        var f = fixture();
        activity(f, NOW.minus(Duration.ofHours(25)), "IN_PROGRESS");
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a =
                    threads.submit(
                            () -> {
                                start.await();
                                return retention.purgeExpiredCanonicalUtterances();
                            });
            var b =
                    threads.submit(
                            () -> {
                                start.await();
                                return retention.purgeExpiredCanonicalUtterances();
                            });
            start.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS) + b.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(canonical(f)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session))
                .isEqualTo(1);
    }

    /** 완료 서비스 대신 활동 상태·완료 시각·스탬프 지급 시각을 직접 바꿔 테스트를 준비한다. */
    private void stageActivityCompletionFixture(Fixture f) {
        jdbc.queryForObject(
                "select activity_id from activity where activity_id=? for update",
                UUID.class,
                f.activity);
        jdbc.queryForObject(
                "select child_id from child where child_id=? for update", UUID.class, f.child);
        jdbc.update(
                "update activity set status='COMPLETED', completed_at=?, reward_issued_at=? where activity_id=?",
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                f.activity);
    }

    private void seedQuality(Fixture f) {
        jdbc.update(
                "update roleplay_turn set input_quality = '{\"status\":\"ANALYZABLE\"}'::jsonb where session_id=?",
                f.session);
    }

    private void seedFailure(Fixture f) {
        jdbc.update(
                "insert into roleplay_voice_input_state(session_id,failure_count) values(?,1)",
                f.session);
        jdbc.update(
                "insert into roleplay_voice_input_failure(session_id,idempotency_key,input_fingerprint,checkpoint_version,failure_reason,failure_count_after,recorded_at) values(?,?,?,0,'NO_SPEECH',1,?)",
                f.session,
                UUID.randomUUID(),
                "c".repeat(64),
                Timestamp.from(NOW));
    }

    private long failureStateCount(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_voice_input_state where session_id=?",
                Long.class,
                f.session);
    }

    private boolean hasQuality(Fixture f) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select input_quality is not null from roleplay_turn where session_id=?",
                        Boolean.class,
                        f.session));
    }

    private String canonical(Fixture f) {
        return jdbc.queryForObject(
                "select canonical_utterance from roleplay_turn where session_id=?",
                String.class,
                f.session);
    }

    private void activity(Fixture f, Instant time, String status) {
        jdbc.update(
                "update roleplay_session set last_activity_at=?,status=? where session_id=?",
                Timestamp.from(time),
                status,
                f.session);
    }

    private record Fixture(UUID session, UUID activity, UUID child, UUID scenario, UUID key) {}

    private AiTraceContext trace(Fixture f) {
        return new AiTraceContext(
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
    }

    private RoleplayTurnResult.SpokenReady spoken(AiTraceContext trace) {
        UUID goal = UUID.randomUUID(), candidate = UUID.randomUUID();
        var analysis =
                new AnalysisResult(
                        trace.turnId(),
                        "ANSWER_ATTEMPT",
                        "PARTIALLY_RELEVANT",
                        "PARTIAL_UNDERSTANDING",
                        new AnalysisResult.PrimaryGap("MISSING_EMOTION", null),
                        new AnalysisResult.NextStrategy("PROBE_EMOTION", goal, null, "S1"),
                        0.9);
        var response =
                new CandidateResponse(
                        candidate,
                        trace.turnId(),
                        "synthetic approved response",
                        "GUIDING_QUESTION",
                        "PROBE_EMOTION",
                        goal,
                        "S1",
                        List.of());
        var evaluation =
                new EvaluationResult(
                        candidate, true, EvaluationDecision.PASS, null, 0, List.of(), null);
        return new RoleplayTurnResult.SpokenReady(
                new RoleplayTurnResult.Ready(analysis, response, evaluation),
                "synthetic canonical input",
                null);
    }

    private Fixture fixture() {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID();
        var f =
                new Fixture(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        child,
                        UUID.randomUUID(),
                        UUID.randomUUID());
        Timestamp now = Timestamp.from(NOW);
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
                f.activity,
                child,
                goal,
                f.scenario,
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                f.session,
                f.activity,
                child,
                f.scenario,
                now);
        var trace = trace(f);
        store.commit(
                RoleplayCheckpointCommand.fromApproved(
                        trace, 0, 1, f.key, "a".repeat(64), spoken(trace)),
                RoleplayTurnDeadline.start());
        activity(f, NOW, "IN_PROGRESS");
        return f;
    }
}
