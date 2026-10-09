package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnRunner;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class RoleplayCheckpointIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void defaultRetentionCleanupIsRegisteredWithScheduler() {
        assertThat(
                        context.getBean(
                                com.neuringo.neuringobe.roleplay.service
                                        .RoleplayCanonicalRetentionJob.class))
                .isNotNull();
        assertThat(
                        context.getBean(
                                        org.springframework.scheduling.annotation
                                                .ScheduledAnnotationBeanPostProcessor.class)
                                .getScheduledTasks())
                .isNotEmpty();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RoleplayCheckpointStore store;
    @Autowired com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestGuard guard;
    @Autowired DataSource dataSource;

    @Test
    void commitsTurnAndSessionCheckpointWithSameApprovedCandidate() {
        var fixture = fixture();
        var command = command(fixture, UUID.randomUUID(), "a".repeat(64), 0, 1);
        var saved =
                (RoleplayCheckpointStore.Committed)
                        store.commit(command, RoleplayTurnDeadline.start());
        assertThat(saved.replayed()).isFalse();
        assertThat(saved.candidateId()).isEqualTo(command.candidateId());
        assertThat(saved.version()).isEqualTo(1);
        assertThat(count(fixture)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_id from roleplay_session where session_id=?",
                                UUID.class,
                                fixture.session))
                .isEqualTo(command.turnId());
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                fixture.session))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select canonical_utterance from roleplay_turn where turn_id=?",
                                String.class,
                                command.turnId()))
                .isEqualTo("synthetic canonical input");
    }

    @Test
    void replayUsesDedicatedKeyAndOriginalCandidateEvenWithNewTraceAndNewCandidate() {
        var fixture = fixture();
        UUID key = UUID.randomUUID();
        var first =
                (RoleplayCheckpointStore.Committed)
                        store.commit(
                                command(fixture, key, "a".repeat(64), 0, 1),
                                RoleplayTurnDeadline.start());
        var replay =
                (RoleplayCheckpointStore.Committed)
                        store.commit(
                                command(fixture, key, "a".repeat(64), 0, 1),
                                RoleplayTurnDeadline.start());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.candidateId()).isEqualTo(first.candidateId());
        assertThat(replay.version()).isEqualTo(1);
        assertThat(count(fixture)).isEqualTo(1);
    }

    @Test
    void sameKeyWithChangedInputIsConflict() {
        var fixture = fixture();
        UUID key = UUID.randomUUID();
        store.commit(command(fixture, key, "a".repeat(64), 0, 1), RoleplayTurnDeadline.start());
        assertThat(
                        store.commit(
                                command(fixture, key, "b".repeat(64), 0, 1),
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(count(fixture)).isEqualTo(1);
    }

    @Test
    void staleVersionAndSkippedTurnDoNotWrite() {
        var fixture = fixture();
        assertThat(
                        store.commit(
                                command(fixture, UUID.randomUUID(), "a".repeat(64), 1, 1),
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(
                        store.commit(
                                command(fixture, UUID.randomUUID(), "a".repeat(64), 0, 2),
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(count(fixture)).isZero();
    }

    @Test
    void anotherChildCannotCommitToTheSession() {
        var original = fixture();
        var forged =
                new Fixture(
                        original.session, original.activity, UUID.randomUUID(), original.scenario);
        assertThat(
                        store.commit(
                                command(forged, UUID.randomUUID(), "a".repeat(64), 0, 1),
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(count(original)).isZero();
    }

    @Test
    void sessionWriteFailureRollsBackEarlierTurnInsert() {
        var fixture = fixture();
        String constraint = "test_rollback_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table roleplay_session add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + fixture.session
                        + "'::uuid or last_turn_number = 0)");
        try {
            assertThatThrownBy(
                            () ->
                                    store.commit(
                                            command(
                                                    fixture,
                                                    UUID.randomUUID(),
                                                    "a".repeat(64),
                                                    0,
                                                    1),
                                            RoleplayTurnDeadline.start()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count(fixture)).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select row_version from roleplay_session where session_id=?",
                                    Long.class,
                                    fixture.session))
                    .isZero();
        } finally {
            jdbc.execute("alter table roleplay_session drop constraint " + constraint);
        }
    }

    @Test
    void concurrentIdenticalKeyCreatesOnlyOneTurnAndOneVersionIncrement() throws Exception {
        var fixture = fixture();
        UUID key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a =
                    threads.submit(
                            () -> {
                                start.await();
                                return store.commit(
                                        command(fixture, key, "a".repeat(64), 0, 1),
                                        RoleplayTurnDeadline.start());
                            });
            var b =
                    threads.submit(
                            () -> {
                                start.await();
                                return store.commit(
                                        command(fixture, key, "a".repeat(64), 0, 1),
                                        RoleplayTurnDeadline.start());
                            });
            start.countDown();
            var results =
                    List.of(
                            (RoleplayCheckpointStore.Committed) a.get(10, TimeUnit.SECONDS),
                            (RoleplayCheckpointStore.Committed) b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RoleplayCheckpointStore.Committed::replayed).count())
                    .isEqualTo(1);
            assertThat(
                            results.stream()
                                    .map(RoleplayCheckpointStore.Committed::candidateId)
                                    .distinct())
                    .hasSize(1);
            assertThat(count(fixture)).isEqualTo(1);
        }
    }

    @Test
    void concurrentDifferentKeysCannotBothAdvanceTheSameVersion() throws Exception {
        var fixture = fixture();
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a =
                    threads.submit(
                            () -> {
                                start.await();
                                return store.commit(
                                        command(fixture, UUID.randomUUID(), "a".repeat(64), 0, 1),
                                        RoleplayTurnDeadline.start());
                            });
            var b =
                    threads.submit(
                            () -> {
                                start.await();
                                return store.commit(
                                        command(fixture, UUID.randomUUID(), "b".repeat(64), 0, 1),
                                        RoleplayTurnDeadline.start());
                            });
            start.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(
                            results.stream()
                                    .filter(RoleplayCheckpointStore.Committed.class::isInstance)
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            results.stream()
                                    .filter(RoleplayCheckpointStore.Conflict.class::isInstance)
                                    .count())
                    .isEqualTo(1);
            assertThat(count(fixture)).isEqualTo(1);
        }
    }

    @Test
    void lockWaitingExpiresWithoutPersistingATurn() throws Exception {
        var fixture = fixture();
        try (var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement =
                    lock.prepareStatement(
                            "select session_id from roleplay_session where session_id=? for update")) {
                statement.setObject(1, fixture.session);
                statement.executeQuery().close();
            }
            var result =
                    store.commit(
                            command(fixture, UUID.randomUUID(), "a".repeat(64), 0, 1),
                            RoleplayTurnDeadline.start(Duration.ofMillis(100)));
            assertThat(result).isInstanceOf(RoleplayCheckpointStore.Expired.class);
            lock.rollback();
        }
        assertThat(count(fixture)).isZero();
    }

    @Test
    void expiredBudgetDoesNotWrite() {
        var fixture = fixture();
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        assertThat(
                        store.commit(
                                command(fixture, UUID.randomUUID(), "a".repeat(64), 0, 1),
                                deadline))
                .isInstanceOf(RoleplayCheckpointStore.Expired.class);
        assertThat(count(fixture)).isZero();
    }

    @Test
    void checkpointedExecutorReturnsSuccessOnlyAfterDatabaseCommitAndReplaysStoredText() {
        var fixture = fixture();
        UUID key = UUID.randomUUID();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            guard);
            var firstTrace = trace(fixture);
            var first =
                    (RoleplayCheckpointedTurnExecutor.Success)
                            executor.execute(
                                    firstTrace,
                                    0,
                                    1,
                                    key,
                                    "a".repeat(64),
                                    RoleplayTurnDeadline.start(),
                                    deadline -> spoken(firstTrace));
            assertThat(count(fixture)).isEqualTo(1);
            assertThat(first.replayed()).isFalse();
            var secondTrace = trace(fixture);
            var second =
                    (RoleplayCheckpointedTurnExecutor.Success)
                            executor.execute(
                                    secondTrace,
                                    0,
                                    1,
                                    key,
                                    "a".repeat(64),
                                    RoleplayTurnDeadline.start(),
                                    deadline -> spoken(secondTrace));
            assertThat(second.replayed()).isTrue();
            assertThat(second.outcome()).isInstanceOf(RoleplayTurnOutcome.ApprovedResponse.class);
            assertThat(((RoleplayTurnOutcome.ApprovedResponse) second.outcome()).candidateId())
                    .isEqualTo(
                            ((RoleplayTurnOutcome.SpokenResponse) first.outcome())
                                    .response()
                                    .candidateId());
        }
    }

    private int count(Fixture fixture) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=?",
                Integer.class,
                fixture.session);
    }

    @Test
    void concurrentSameRequestRunsPipelineOnlyOnceAndThenReplaysWithoutAnotherCall()
            throws Exception {
        var f = fixture();
        UUID key = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2);
                var callers = Executors.newSingleThreadExecutor()) {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            guard);
            var firstTrace = trace(f);
            var first =
                    callers.submit(
                            () ->
                                    executor.execute(
                                            firstTrace,
                                            0,
                                            1,
                                            key,
                                            "a".repeat(64),
                                            RoleplayTurnDeadline.start(),
                                            deadline -> {
                                                calls.incrementAndGet();
                                                entered.countDown();
                                                try {
                                                    release.await();
                                                } catch (InterruptedException failure) {
                                                    throw new IllegalStateException(failure);
                                                }
                                                return spoken(firstTrace);
                                            }));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var second =
                        executor.execute(
                                trace(f),
                                0,
                                1,
                                key,
                                "a".repeat(64),
                                RoleplayTurnDeadline.start(),
                                deadline -> {
                                    throw new AssertionError("Busy request must not execute AI");
                                });
                assertThat(second).isInstanceOf(RoleplayCheckpointedTurnExecutor.Processing.class);
                assertThat(count(f)).isZero();
                release.countDown();
                var original =
                        (RoleplayCheckpointedTurnExecutor.Success) first.get(5, TimeUnit.SECONDS);
                var replay =
                        (RoleplayCheckpointedTurnExecutor.Success)
                                executor.execute(
                                        trace(f),
                                        0,
                                        1,
                                        key,
                                        "a".repeat(64),
                                        RoleplayTurnDeadline.start(),
                                        deadline -> {
                                            throw new AssertionError("Replay must not execute AI");
                                        });
                assertThat(replay.replayed()).isTrue();
                assertThat(((RoleplayTurnOutcome.ApprovedResponse) replay.outcome()).candidateId())
                        .isEqualTo(
                                ((RoleplayTurnOutcome.SpokenResponse) original.outcome())
                                        .response()
                                        .candidateId());
                assertThat(calls.get()).isEqualTo(1);
                assertThat(count(f)).isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void timedOutUncooperativeWorkerRetainsOwnershipUntilItActuallyExits() throws Exception {
        var f = fixture();
        UUID key = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            guard);
            var trace = trace(f);
            var result =
                    executor.execute(
                            trace,
                            0,
                            1,
                            key,
                            "a".repeat(64),
                            RoleplayTurnDeadline.start(Duration.ofSeconds(1)),
                            deadline -> {
                                entered.countDown();
                                boolean done = false;
                                while (!done)
                                    try {
                                        release.await();
                                        done = true;
                                    } catch (InterruptedException ignored) {
                                    }
                                return spoken(trace);
                            });
            assertThat(result).isInstanceOf(RoleplayCheckpointedTurnExecutor.Recovery.class);
            assertThat(entered.getCount()).isZero();
            var duplicate =
                    executor.execute(
                            trace(f),
                            0,
                            1,
                            key,
                            "a".repeat(64),
                            RoleplayTurnDeadline.start(),
                            deadline -> {
                                throw new AssertionError("Late worker still owns the request");
                            });
            assertThat(duplicate).isInstanceOf(RoleplayCheckpointedTurnExecutor.Processing.class);
            assertThat(count(f)).isZero();
            release.countDown();
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
        var identity =
                new com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity(
                        trace(f), key, "a".repeat(64), 0, 1);
        try (var lease = guard.tryAcquire(identity, RoleplayTurnDeadline.start()).orElseThrow()) {
            assertThat(count(f)).isZero();
        }
    }

    @Test
    void independentGuardInstancesShareDatabaseOwnershipAndReleaseItAfterFailure() {
        var f = fixture();
        var identity =
                new com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity(
                        trace(f), UUID.randomUUID(), "a".repeat(64), 0, 1);
        var databaseGuard =
                new com.neuringo.neuringobe.roleplay.service.PostgresRoleplayRequestGuard(
                        dataSource);
        var otherGuard =
                new com.neuringo.neuringobe.roleplay.service.PostgresRoleplayRequestGuard(
                        dataSource);
        try (var lease =
                databaseGuard.tryAcquire(identity, RoleplayTurnDeadline.start()).orElseThrow()) {
            assertThat(otherGuard.tryAcquire(identity, RoleplayTurnDeadline.start())).isEmpty();
        }
        try (var lease =
                otherGuard.tryAcquire(identity, RoleplayTurnDeadline.start()).orElseThrow()) {}
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            databaseGuard);
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            identity.trace(),
                                            0,
                                            1,
                                            identity.idempotencyKey(),
                                            "a".repeat(64),
                                            RoleplayTurnDeadline.start(),
                                            deadline -> {
                                                throw new IllegalStateException(
                                                        "synthetic AI failure");
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("synthetic AI failure");
        }
        try (var lease =
                otherGuard.tryAcquire(identity, RoleplayTurnDeadline.start()).orElseThrow()) {}
    }

    @Test
    void inputKeyConflictAndForeignScopeAreBlockedBeforePipeline() {
        var f = fixture();
        UUID key = UUID.randomUUID();
        store.commit(command(f, key, "a".repeat(64), 0, 1), RoleplayTurnDeadline.start());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            guard);
            assertThat(
                            executor.execute(
                                    trace(f),
                                    0,
                                    1,
                                    key,
                                    "b".repeat(64),
                                    RoleplayTurnDeadline.start(),
                                    deadline -> {
                                        throw new AssertionError(
                                                "Changed key input must not execute AI");
                                    }))
                    .isInstanceOf(RoleplayCheckpointedTurnExecutor.Conflict.class);
            var foreign = new Fixture(f.session, f.activity, UUID.randomUUID(), f.scenario);
            assertThat(
                            executor.execute(
                                    trace(foreign),
                                    0,
                                    1,
                                    key,
                                    "a".repeat(64),
                                    RoleplayTurnDeadline.start(),
                                    deadline -> {
                                        throw new AssertionError(
                                                "Foreign scope must not execute AI");
                                    }))
                    .isInstanceOf(RoleplayCheckpointedTurnExecutor.Conflict.class);
        }
    }

    @Test
    void guardReservesConnectionsForCommitAndReportsCapacityWithoutStartingWork() {
        var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
        var localGuard =
                new com.neuringo.neuringobe.roleplay.service.PostgresRoleplayRequestGuard(
                        dataSource);
        var f = fixture();
        var leases =
                new java.util.ArrayList<
                        com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestGuard
                                .Lease>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize() / 2; i++) {
                var identity =
                        new com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity(
                                trace(f), UUID.randomUUID(), "a".repeat(64), 0, 1);
                leases.add(
                        localGuard
                                .tryAcquire(identity, RoleplayTurnDeadline.start())
                                .orElseThrow());
            }
            try (var workers = Executors.newSingleThreadExecutor()) {
                var executor =
                        new RoleplayCheckpointedTurnExecutor(
                                new RoleplayTurnRunner(workers),
                                store,
                                new RoleplayTurnOutcomeResolver(
                                        new RoleplayNoticeCatalog(List.of())),
                                localGuard);
                assertThat(
                                executor.execute(
                                        trace(f),
                                        0,
                                        1,
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        RoleplayTurnDeadline.start(),
                                        deadline -> {
                                            throw new AssertionError(
                                                    "No capacity must not execute AI");
                                        }))
                        .isInstanceOf(RoleplayCheckpointedTurnExecutor.CapacityUnavailable.class);
            }
            assertThat(count(f))
                    .isZero(); // Query still has a connection while guard slots are full.
        } finally {
            leases.forEach(
                    com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestGuard.Lease
                            ::close);
        }
    }

    private RoleplayCheckpointCommand command(
            Fixture fixture, UUID key, String fingerprint, long version, int number) {
        var trace = trace(fixture);
        return RoleplayCheckpointCommand.fromApproved(
                trace, version, number, key, fingerprint, spoken(trace));
    }

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
        UUID goal = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
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

    private record Fixture(UUID session, UUID activity, UUID child, UUID scenario) {}

    private Fixture fixture() {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID(),
                session = UUID.randomUUID(),
                scenario = UUID.randomUUID();
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
                "insert into activity(activity_id,child_id,goal_id,scenario_id,status,assigned_at) values(?,?,?,?,'IN_PROGRESS',?)",
                activity,
                child,
                goal,
                scenario,
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                session,
                activity,
                child,
                scenario,
                now);
        return new Fixture(session, activity, child, scenario);
    }
}
