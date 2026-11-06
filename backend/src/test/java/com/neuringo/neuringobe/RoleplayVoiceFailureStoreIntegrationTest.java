package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.deletion.service.DeletionService;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.roleplay.repository.RoleplayTurnRepository;
import com.neuringo.neuringobe.roleplay.service.RoleplayVoiceFailureStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class RoleplayVoiceFailureStoreIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired RoleplayVoiceFailureStore failures;
    @Autowired com.neuringo.neuringobe.roleplay.application.RoleplayVoiceRecoverySource recoveries;
    @Autowired RoleplayCheckpointStore checkpoints;
    @Autowired ActivityRepository activities;
    @Autowired ChildRepository children;
    @Autowired RoleplaySessionRepository sessions;
    @Autowired RoleplayTurnRepository turns;
    @Autowired PlatformTransactionManager manager;
    @Autowired DeletionService deletion;

    @Test
    void storesMixedFailuresWithoutAdvancingLearningAndRestoresOriginalResult() {
        var f = fixture(jdbc);
        var first = identity(f, UUID.randomUUID(), "a");
        var snapshot = learningSnapshot(f);
        assertThat(failures.findRecorded(first, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Absent.class);
        for (var reason :
                List.of(
                        InputFailure.LOW_CONFIDENCE,
                        InputFailure.NO_SPEECH,
                        InputFailure.TRANSCRIPTION_FAILED)) {
            var id =
                    reason == InputFailure.LOW_CONFIDENCE
                            ? first
                            : identity(f, UUID.randomUUID(), "b");
            assertThat(failures.recordFailure(id, reason, deadline()))
                    .isInstanceOf(RoleplayVoiceFailureStore.Recorded.class);
        }
        assertThat(count(f)).isEqualTo(3);
        assertThat(learningSnapshot(f)).isEqualTo(snapshot);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_goal_assessment where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select reward_issued_at from activity where activity_id=?",
                                Timestamp.class,
                                f.activity()))
                .isNull();
        var replay =
                (RoleplayVoiceFailureStore.Recorded)
                        failures.recordFailure(first, InputFailure.NO_SPEECH, deadline());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reason()).isEqualTo(InputFailure.LOW_CONFIDENCE);
        assertThat(replay.progress().failureCount()).isEqualTo(1);
        assertThat(replay.currentProgress().failureCount()).isEqualTo(3);
        assertThat(replay.currentProgress().textRecommended()).isTrue();
        assertThat(count(f)).isEqualTo(3);
        var third =
                jdbc.queryForObject(
                        "select failure_count_after from roleplay_voice_input_failure where session_id=? and failure_reason='TRANSCRIPTION_FAILED'",
                        Integer.class,
                        f.session());
        assertThat(third).isEqualTo(3);
    }

    @Test
    void lowConfidenceEvidenceIsAtomicAndReplayCannotReplaceIt() {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        var rejected =
                new RoleplayTurnResult.InputRejected(
                        InputFailure.LOW_CONFIDENCE,
                        new RoleplayTurnResult.SttConfidenceEvidence(0.4, 0.65));
        assertThat(failures.recordFailure(id, rejected, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Recorded.class);
        var original =
                jdbc.queryForMap(
                        "select stt_confidence, stt_confidence_threshold, failure_count_after from roleplay_voice_input_failure where session_id=? and idempotency_key=?",
                        f.session(),
                        id.idempotencyKey());
        assertThat(original.get("stt_confidence")).isEqualTo(0.4);
        assertThat(original.get("stt_confidence_threshold")).isEqualTo(0.65);
        var different =
                new RoleplayTurnResult.InputRejected(
                        InputFailure.LOW_CONFIDENCE,
                        new RoleplayTurnResult.SttConfidenceEvidence(0.1, 0.2));
        assertThat(
                        ((RoleplayVoiceFailureStore.Recorded)
                                        failures.recordFailure(id, different, deadline()))
                                .replayed())
                .isTrue();
        assertThat(
                        jdbc.queryForMap(
                                "select stt_confidence, stt_confidence_threshold, failure_count_after from roleplay_voice_input_failure where session_id=? and idempotency_key=?",
                                f.session(),
                                id.idempotencyKey()))
                .isEqualTo(original);
        assertThat(count(f)).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update roleplay_voice_input_failure set stt_confidence=0.9, stt_confidence_threshold=0.4 where session_id=?",
                                        f.session()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForMap(
                                "select stt_confidence, stt_confidence_threshold, failure_count_after from roleplay_voice_input_failure where session_id=? and idempotency_key=?",
                                f.session(),
                                id.idempotencyKey()))
                .isEqualTo(original);
    }

    @Test
    void newStoreInstanceReadsRecordedFailureFromDatabase() {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        failures.recordFailure(id, InputFailure.NO_SPEECH, deadline());
        var reassembled =
                new RoleplayVoiceFailureStore(
                        activities,
                        children,
                        sessions,
                        turns,
                        jdbc,
                        manager,
                        java.time.Clock.systemUTC());
        var replay = (RoleplayVoiceFailureStore.Recorded) reassembled.findRecorded(id, deadline());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.progress().failureCount()).isEqualTo(1);
        assertThat(count(f)).isEqualTo(1);
    }

    @Test
    void changedInputUnderRecordedKeyConflictsInBothLookupAndSave() {
        var f = fixture(jdbc);
        var key = UUID.randomUUID();
        failures.recordFailure(identity(f, key, "a"), InputFailure.NO_SPEECH, deadline());
        var changed = identity(f, key, "b");
        assertThat(failures.findRecorded(changed, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Conflict.class);
        assertThat(failures.recordFailure(changed, InputFailure.NO_SPEECH, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Conflict.class);
        assertThat(count(f)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = InputFailure.class,
            names = {"INVALID_INPUT"})
    void unclearVoiceInputIsRecordedAsOneFailure(InputFailure reason) {
        var f = fixture(jdbc);
        assertThat(failures.recordFailure(identity(f, UUID.randomUUID(), "a"), reason, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Recorded.class);
        assertThat(count(f)).isEqualTo(1);
    }

    @Test
    void foreignScopeAndScenarioVersionCannotReadOrWriteFailure() {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        var t = id.trace();
        for (var changed :
                List.of(
                        new AiTraceContext(
                                t.requestId(),
                                t.activityId(),
                                null,
                                UUID.randomUUID(),
                                t.scenarioId(),
                                1,
                                t.sessionId(),
                                t.turnId(),
                                null,
                                null),
                        new AiTraceContext(
                                t.requestId(),
                                UUID.randomUUID(),
                                null,
                                t.childId(),
                                t.scenarioId(),
                                1,
                                t.sessionId(),
                                t.turnId(),
                                null,
                                null),
                        new AiTraceContext(
                                t.requestId(),
                                t.activityId(),
                                null,
                                t.childId(),
                                t.scenarioId(),
                                2,
                                t.sessionId(),
                                t.turnId(),
                                null,
                                null))) {
            var foreign =
                    new RoleplayRequestIdentity(
                            changed, id.idempotencyKey(), id.fingerprint(), 0, 1);
            assertThat(failures.findRecorded(foreign, deadline()))
                    .isInstanceOf(RoleplayVoiceFailureStore.Conflict.class);
            assertThat(failures.recordFailure(foreign, InputFailure.NO_SPEECH, deadline()))
                    .isInstanceOf(RoleplayVoiceFailureStore.Conflict.class);
        }
        assertNoFailure(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"child", "activity", "session", "version", "turn", "scenario"})
    void rejectsIneligibleOrStaleRequestWithoutCounting(String changed) {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        switch (changed) {
            case "child" ->
                    jdbc.update("update child set status='PAUSED' where child_id=?", f.child());
            case "activity" ->
                    jdbc.update(
                            "update activity set status='COMPLETED' where activity_id=?",
                            f.activity());
            case "session" ->
                    jdbc.update(
                            "update roleplay_session set status='PAUSED' where session_id=?",
                            f.session());
            case "version" ->
                    id =
                            new RoleplayRequestIdentity(
                                    id.trace(), id.idempotencyKey(), id.fingerprint(), 1, 1);
            case "turn" ->
                    id =
                            new RoleplayRequestIdentity(
                                    id.trace(), id.idempotencyKey(), id.fingerprint(), 0, 2);
            case "scenario" ->
                    jdbc.update(
                            "update activity set scenario_id=? where activity_id=?",
                            UUID.randomUUID(),
                            f.activity());
            default -> throw new AssertionError(changed);
        }
        assertThat(failures.recordFailure(id, InputFailure.NO_SPEECH, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Conflict.class);
        assertNoFailure(f);
    }

    @Test
    void simultaneousSameKeyCountsOnlyOnce() throws Exception {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        var results =
                concurrently(
                        () -> failures.recordFailure(id, InputFailure.NO_SPEECH, deadline()),
                        () -> failures.recordFailure(id, InputFailure.NO_SPEECH, deadline()));
        assertThat(results).allMatch(RoleplayVoiceFailureStore.Recorded.class::isInstance);
        assertThat(results.stream().map(r -> ((RoleplayVoiceFailureStore.Recorded) r).replayed()))
                .containsExactlyInAnyOrder(false, true);
        assertThat(count(f)).isEqualTo(1);
        assertThat(recordCount(f)).isEqualTo(1);
    }

    @Test
    void simultaneousDifferentKeysDoNotLoseAnIncrement() throws Exception {
        var f = fixture(jdbc);
        var a = identity(f, UUID.randomUUID(), "a");
        var b = identity(f, UUID.randomUUID(), "b");
        var results =
                concurrently(
                        () -> failures.recordFailure(a, InputFailure.NO_SPEECH, deadline()),
                        () -> failures.recordFailure(b, InputFailure.LOW_CONFIDENCE, deadline()));
        assertThat(
                        results.stream()
                                .map(
                                        r ->
                                                ((RoleplayVoiceFailureStore.Recorded) r)
                                                        .progress()
                                                        .failureCount()))
                .containsExactlyInAnyOrder(1, 2);
        assertThat(count(f)).isEqualTo(2);
        assertThat(recordCount(f)).isEqualTo(2);
    }

    @Test
    void failedInputKeyCannotLaterCommitAnApprovedTurn() {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        failures.recordFailure(id, InputFailure.NO_SPEECH, deadline());
        assertThat(checkpoints.findConfirmed(id, deadline()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(checkpoints.commit(command(id), deadline()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(count(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_number from roleplay_session where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
    }

    @Test
    void approvedTurnKeyCannotBecomeAnInputFailure() {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        assertThat(checkpoints.commit(command(id), deadline()))
                .isInstanceOf(RoleplayCheckpointStore.Committed.class);
        assertThat(failures.findRecorded(id, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Conflict.class);
        assertThat(failures.recordFailure(id, InputFailure.NO_SPEECH, deadline()))
                .isInstanceOf(RoleplayVoiceFailureStore.Conflict.class);
        assertNoFailure(f);
    }

    @Test
    void racingSuccessAndFailureShareOneTerminalKey() throws Exception {
        var f = fixture(jdbc);
        var id = identity(f, UUID.randomUUID(), "a");
        var results =
                concurrently(
                        () -> failures.recordFailure(id, InputFailure.NO_SPEECH, deadline()),
                        () -> checkpoints.commit(command(id), deadline()));
        int recorded = recordCount(f);
        int committed =
                jdbc.queryForObject(
                        "select count(*) from roleplay_turn where session_id=?",
                        Integer.class,
                        f.session());
        assertThat(recorded + committed).isEqualTo(1);
        assertThat(
                        results.stream()
                                .filter(
                                        r ->
                                                r instanceof RoleplayVoiceFailureStore.Recorded
                                                        || r
                                                                instanceof
                                                                RoleplayCheckpointStore.Committed)
                                .count())
                .isEqualTo(1);
    }

    @Test
    void failedLedgerInsertRollsBackCounterUpdate() {
        var f = fixture(jdbc);
        var constraint = "test_reject_failure_" + f.session().toString().replace("-", "");
        jdbc.execute(
                "alter table roleplay_voice_input_failure add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + f.session()
                        + "'::uuid) not valid");
        try {
            assertThatThrownBy(
                            () ->
                                    failures.recordFailure(
                                            identity(f, UUID.randomUUID(), "a"),
                                            InputFailure.NO_SPEECH,
                                            deadline()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertNoFailure(f);
        } finally {
            jdbc.execute("alter table roleplay_voice_input_failure drop constraint " + constraint);
        }
    }

    @Test
    void expiredRequestCannotCreateFailure() {
        var f = fixture(jdbc);
        var expired = deadline();
        expired.cancel();
        assertThat(
                        failures.recordFailure(
                                identity(f, UUID.randomUUID(), "a"),
                                InputFailure.NO_SPEECH,
                                expired))
                .isInstanceOf(RoleplayVoiceFailureStore.Expired.class);
        assertNoFailure(f);
    }

    @Test
    void childDeletionErasesFailureAndCounterButPreservesAnotherChild() {
        var removed = fixture(jdbc);
        var retained = fixture(jdbc);
        failures.recordFailure(
                identity(removed, UUID.randomUUID(), "a"),
                new RoleplayTurnResult.InputRejected(
                        InputFailure.LOW_CONFIDENCE,
                        new RoleplayTurnResult.SttConfidenceEvidence(0.3, 0.4)),
                deadline());
        failures.recordFailure(
                identity(retained, UUID.randomUUID(), "b"), InputFailure.NO_SPEECH, deadline());
        deletion.deleteChild(removed.instructor(), removed.child());
        assertNoFailure(removed);
        assertThat(count(retained)).isEqualTo(1);
        assertThat(recordCount(retained)).isEqualTo(1);
    }

    @Test
    void deletionRollbackRestoresFailureAndCounter() {
        var f = fixture(jdbc);
        failures.recordFailure(
                identity(f, UUID.randomUUID(), "a"), InputFailure.NO_SPEECH, deadline());
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                tx -> {
                                                    deletion.deleteChild(f.instructor(), f.child());
                                                    assertNoFailure(f);
                                                    throw new IllegalStateException("합성 삭제 실패");
                                                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("합성 삭제 실패");
        assertThat(count(f)).isEqualTo(1);
        assertThat(recordCount(f)).isEqualTo(1);
    }

    @Test
    void upgradeFromV25DoesNotInventEvidenceForOldFailures() throws Exception {
        String schema = "voice_evidence_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection()) {
            jdbc.execute("create schema " + schema);
            try {
                connection.setSchema(schema);
                var old = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("25")
                        .load()
                        .migrate();
                var f = fixture(old);
                var key = UUID.randomUUID();
                old.update(
                        "insert into roleplay_voice_input_state(session_id, failure_count) values (?, 1)",
                        f.session());
                old.update(
                        "insert into roleplay_voice_input_failure(session_id,idempotency_key,input_fingerprint,checkpoint_version,failure_reason,failure_count_after,recorded_at) values (?,?,?,?,?,?,?)",
                        f.session(),
                        key,
                        "a".repeat(64),
                        0,
                        "LOW_CONFIDENCE",
                        1,
                        Timestamp.from(Instant.now()));
                var oldTurn = UUID.randomUUID();
                old.update(
                        "insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,turn_number,candidate_id,micro_goal_id,support_level,response_text,canonical_utterance,checkpoint_version,committed_at) values (?,?,?,?,?,1,?,?,'S1',?,?,1,?)",
                        oldTurn,
                        f.session(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "b".repeat(64),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "기존 응답",
                        "기존 표준 발화",
                        Timestamp.from(Instant.now()));
                old.update(
                        "update roleplay_session set last_turn_id=?,last_turn_number=1,row_version=1 where session_id=?",
                        oldTurn,
                        f.session());
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load()
                        .migrate();
                var row =
                        old.queryForMap(
                                "select failure_reason, failure_count_after, stt_confidence, stt_confidence_threshold from roleplay_voice_input_failure where session_id=? and idempotency_key=?",
                                f.session(),
                                key);
                assertThat(row.get("failure_reason")).isEqualTo("LOW_CONFIDENCE");
                assertThat(row.get("failure_count_after")).isEqualTo(1);
                assertThat(row.get("stt_confidence")).isNull();
                assertThat(row.get("stt_confidence_threshold")).isNull();
                var oldQuality =
                        old.queryForMap(
                                "select canonical_utterance from roleplay_turn where turn_id=?",
                                oldTurn);
                assertThat(oldQuality.get("canonical_utterance")).isEqualTo("기존 표준 발화");
            } finally {
                connection.setSchema("public");
                jdbc.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test
    void upgradeFromV24PreservesExistingSession() throws Exception {
        String schema = "voice_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection()) {
            jdbc.execute("create schema " + schema);
            try {
                connection.setSchema(schema);
                var old = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("24")
                        .load()
                        .migrate();
                var f = fixture(old);
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load()
                        .migrate();
                assertThat(
                                old.queryForObject(
                                        "select count(*) from roleplay_session where session_id=?",
                                        Integer.class,
                                        f.session()))
                        .isEqualTo(1);
                assertThat(
                                old.queryForObject(
                                        "select count(*) from roleplay_voice_input_state",
                                        Integer.class))
                        .isZero();
                assertThat(
                                old.queryForObject(
                                        "select count(*) from roleplay_voice_input_failure",
                                        Integer.class))
                        .isZero();
            } finally {
                connection.setSchema("public");
                jdbc.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private List<Object> concurrently(Callable<Object> first, Callable<Object> second)
            throws Exception {
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a =
                    workers.submit(
                            () -> {
                                gate.await();
                                return first.call();
                            });
            var b =
                    workers.submit(
                            () -> {
                                gate.await();
                                return second.call();
                            });
            gate.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    private int count(Fixture f) {
        return jdbc
                .queryForList(
                        "select failure_count from roleplay_voice_input_state where session_id=?",
                        Integer.class,
                        f.session())
                .stream()
                .findFirst()
                .orElse(0);
    }

    private int recordCount(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_voice_input_failure where session_id=?",
                Integer.class,
                f.session());
    }

    private void assertNoFailure(Fixture f) {
        assertThat(recordCount(f)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_state where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
    }

    private java.util.Map<String, Object> learningSnapshot(Fixture fixture) {
        var result =
                new java.util.HashMap<>(
                        jdbc.queryForMap(
                                "select * from roleplay_session where session_id=?",
                                fixture.session()));
        result.remove("last_activity_at");
        return result;
    }

    private static RoleplayTurnDeadline deadline() {
        return RoleplayTurnDeadline.start();
    }

    @Test
    void recoveryReadsCurrentRecommendationWithoutCreatingStateOrAdvancingTheSession() {
        var f = fixture(jdbc);
        var key =
                new com.neuringo.neuringobe.roleplay.application.RoleplayVoiceRecoverySource.Key(
                        f.child(), f.activity(), f.session(), f.scenario(), 1);
        assertThat(recoveries.textRecommended(key, RoleplayTurnDeadline.start())).isFalse();
        assertThat(recoveries.progress(key, RoleplayTurnDeadline.start()).failureCount()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_state where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        var session = learningSnapshot(f);
        for (int i = 0; i < 3; i++) {
            failures.recordFailure(
                    identity(f, UUID.randomUUID(), "a"),
                    InputFailure.NO_SPEECH,
                    RoleplayTurnDeadline.start());
            assertThat(recoveries.textRecommended(key, RoleplayTurnDeadline.start()))
                    .isEqualTo(i == 2);
            assertThat(recoveries.progress(key, RoleplayTurnDeadline.start()).failureCount())
                    .isEqualTo(i + 1);
        }
        assertThat(learningSnapshot(f)).isEqualTo(session);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_failure where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(3);
        var expired = RoleplayTurnDeadline.start();
        expired.cancel();
        assertThatThrownBy(() -> recoveries.textRecommended(key, expired))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"child", "activity", "session", "scenario", "version"})
    void recoveryCannotReadAnotherOwnerOrScenario(String changed) {
        var f = fixture(jdbc);
        var key =
                new com.neuringo.neuringobe.roleplay.application.RoleplayVoiceRecoverySource.Key(
                        changed.equals("child") ? UUID.randomUUID() : f.child(),
                        changed.equals("activity") ? UUID.randomUUID() : f.activity(),
                        changed.equals("session") ? UUID.randomUUID() : f.session(),
                        changed.equals("scenario") ? UUID.randomUUID() : f.scenario(),
                        changed.equals("version") ? 2 : 1);
        assertThatThrownBy(() -> recoveries.textRecommended(key, RoleplayTurnDeadline.start()))
                .isInstanceOf(com.neuringo.neuringobe.common.ResourceNotFoundException.class);
    }

    private RoleplayRequestIdentity identity(Fixture f, UUID key, String fingerprint) {
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        f.activity(),
                        null,
                        f.child(),
                        f.scenario(),
                        1,
                        f.session(),
                        UUID.randomUUID(),
                        null,
                        null);
        return new RoleplayRequestIdentity(trace, key, fingerprint.repeat(64), 0, 1);
    }

    private RoleplayCheckpointCommand command(RoleplayRequestIdentity id) {
        UUID goal = UUID.randomUUID(), candidate = UUID.randomUUID();
        var analysis =
                new AnalysisResult(
                        id.trace().turnId(),
                        "ANSWER_ATTEMPT",
                        "PARTIALLY_RELEVANT",
                        "PARTIAL_UNDERSTANDING",
                        new AnalysisResult.PrimaryGap("MISSING_EMOTION", null),
                        new AnalysisResult.NextStrategy("PROBE_EMOTION", goal, null, "S1"),
                        0.9);
        var response =
                new CandidateResponse(
                        candidate,
                        id.trace().turnId(),
                        "합성 검증 응답",
                        "GUIDING_QUESTION",
                        "PROBE_EMOTION",
                        goal,
                        "S1",
                        List.of());
        var evaluation =
                new EvaluationResult(
                        candidate, true, EvaluationDecision.PASS, null, 0, List.of(), null);
        var spoken =
                new RoleplayTurnResult.SpokenReady(
                        new RoleplayTurnResult.Ready(analysis, response, evaluation),
                        "합성 입력",
                        null);
        return RoleplayCheckpointCommand.fromApproved(
                id.trace(),
                id.expectedVersion(),
                id.turnNumber(),
                id.idempotencyKey(),
                id.fingerprint(),
                spoken);
    }

    private record Fixture(
            UUID instructor, UUID session, UUID activity, UUID child, UUID scenario) {}

    private Fixture fixture(JdbcTemplate db) {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID(),
                session = UUID.randomUUID(),
                scenario = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        db.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)",
                user,
                user + "@example.test",
                "test-hash",
                now);
        db.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                user);
        db.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        db.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,?)",
                goal,
                child,
                user,
                "a".repeat(64),
                now);
        db.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,assigned_at) values(?,?,?,?,'ROLEPLAY','IN_PROGRESS',?)",
                activity,
                child,
                goal,
                scenario,
                now);
        db.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,current_support_level,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS','S1',?)",
                session,
                activity,
                child,
                scenario,
                now);
        return new Fixture(user, session, activity, child, scenario);
    }
}
