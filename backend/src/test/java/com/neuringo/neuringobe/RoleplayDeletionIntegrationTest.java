package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.deletion.service.DeletionService;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real commits: no test-level transaction hiding deferred FK failures. */
@IntegrationTest
class RoleplayDeletionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired DeletionService deletion;
    @Autowired TransactionTemplate transactions;

    @Test
    void deletesChildWithLastTurnReferenceAndPreservesSibling() {
        var removed = fixture(jdbc);
        var sibling = child(jdbc, removed.instructor(), removed.classroom());
        assertGraph(jdbc, removed, 1);
        assertGraph(jdbc, sibling, 1);

        deletion.deleteChild(removed.instructor(), removed.child());

        assertGraph(jdbc, removed, 0);
        assertGraph(jdbc, sibling, 1);
    }

    @Test
    void deletesClassroomWithRoleplayRecordsAndPreservesOtherClassroom() {
        var first = fixture(jdbc);
        var second = child(jdbc, first.instructor(), first.classroom());
        var other = fixture(jdbc);

        deletion.deleteClassroom(first.instructor(), first.classroom());

        assertGraph(jdbc, first, 0);
        assertGraph(jdbc, second, 0);
        assertThat(count(jdbc, "classroom", "class_id", first.classroom())).isZero();
        assertGraph(jdbc, other, 1);
        assertThat(count(jdbc, "classroom", "class_id", other.classroom())).isEqualTo(1);
    }

    @Test
    void rejectsActivityOrSessionDeletionWithoutDeletingDependents() {
        var f = fixture(jdbc);
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        tx ->
                                                jdbc.update(
                                                        "delete from activity where activity_id = ?",
                                                        f.activity())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        tx ->
                                                jdbc.update(
                                                        "delete from roleplay_session where session_id = ?",
                                                        f.session())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertGraph(jdbc, f, 1);
    }

    @Test
    void rollsBackAllDeletedRecordsWhenEnclosingTransactionFails() {
        var f = fixture(jdbc);
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        tx -> {
                                            deletion.deleteChild(f.instructor(), f.child());
                                            assertGraph(jdbc, f, 0);
                                            throw new IllegalStateException(
                                                    "synthetic failure after deletion");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic failure after deletion");
        assertGraph(jdbc, f, 1);
    }

    @Test
    void rejectsTurnOnlyDeletionAtCommitBecauseSessionStillReferencesLastTurn() {
        var f = fixture(jdbc);
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        tx -> {
                                            jdbc.update(
                                                    "delete from roleplay_turn where turn_id = ?",
                                                    f.turn());
                                            assertThat(
                                                            count(
                                                                    jdbc,
                                                                    "roleplay_turn",
                                                                    "turn_id",
                                                                    f.turn()))
                                                    .isZero();
                                            // The statement succeeds, but deferred last-turn FK
                                            // validation must fail at commit.
                                        }))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("fk_session_last_turn");
        assertGraph(jdbc, f, 1);
    }

    @Test
    void freshDatabaseUsesNoActionAndKeepsDeferredLastTurnConstraint() {
        assertThat(
                        jdbc.queryForObject(
                                """
                select count(*) from flyway_schema_history where version = '8' and success
                """,
                                Integer.class))
                .isEqualTo(1);
        assertNoAction(jdbc);
        assertThat(
                        jdbc.queryForObject(
                                """
                select condeferrable and condeferred from pg_constraint
                where conname = 'fk_session_last_turn' and conrelid = 'roleplay_session'::regclass
                """,
                                Boolean.class))
                .isTrue();
    }

    @Test
    void upgradesExistingV7DataToV8WithoutChangingStoredRecords() throws Exception {
        String schema = "roleplay_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var pool = (HikariDataSource) dataSource;
        // Never return a schema-switched connection to the application pool.
        var upgradeDataSource =
                new DriverManagerDataSource(
                        pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        try {
            Flyway.configure()
                    .dataSource(upgradeDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("7")
                    .load()
                    .migrate();
            try (var connection = upgradeDataSource.getConnection()) {
                connection.setSchema(schema);
                var isolated = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                var f = fixture(isolated);
                assertThat(
                                isolated.queryForObject(
                                        """
                        select count(*) from pg_constraint where confdeltype = 'c'
                        and conrelid in ('roleplay_session'::regclass, 'roleplay_turn'::regclass)
                        """,
                                        Integer.class))
                        .isEqualTo(2);

                var migration =
                        Flyway.configure()
                                .dataSource(upgradeDataSource)
                                .schemas(schema)
                                .defaultSchema(schema)
                                .target("8")
                                .load()
                                .migrate();

                assertThat(migration.migrationsExecuted).isEqualTo(1);
                assertGraph(isolated, f, 1);
                assertNoAction(isolated);
                assertThatThrownBy(
                                () ->
                                        isolated.update(
                                                "delete from activity where activity_id = ?",
                                                f.activity()))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertGraph(isolated, f, 1);
            }
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private void assertNoAction(JdbcTemplate db) {
        assertThat(
                        db.queryForList(
                                """
                select confdeltype::text from pg_constraint
                where conname in ('fk_roleplay_session_activity_child', 'fk_roleplay_turn_session')
                and conrelid in ('roleplay_session'::regclass, 'roleplay_turn'::regclass)
                """,
                                String.class))
                .containsExactlyInAnyOrder("a", "a");
    }

    private void assertGraph(JdbcTemplate db, Fixture f, long expected) {
        assertThat(count(db, "child", "child_id", f.child())).isEqualTo(expected);
        assertThat(count(db, "learning_goal", "goal_id", f.goal())).isEqualTo(expected);
        assertThat(count(db, "activity", "activity_id", f.activity())).isEqualTo(expected);
        assertThat(count(db, "roleplay_session", "session_id", f.session())).isEqualTo(expected);
        assertThat(count(db, "roleplay_turn", "turn_id", f.turn())).isEqualTo(expected);
        if (expected == 1) {
            assertThat(
                            db.queryForObject(
                                    "select last_turn_id from roleplay_session where session_id = ?",
                                    UUID.class,
                                    f.session()))
                    .isEqualTo(f.turn());
        }
    }

    private long count(JdbcTemplate db, String table, String column, UUID id) {
        return db.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Long.class, id);
    }

    private Fixture fixture(JdbcTemplate db) {
        UUID instructor = UUID.randomUUID(), classroom = UUID.randomUUID();
        db.update(
                """
                insert into user_account(user_id,email,password_hash,name,role,status,created_at)
                values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)
                """,
                instructor,
                instructor + "@example.com",
                "test-hash",
                Timestamp.from(Instant.now()));
        db.update(
                """
                insert into classroom(class_id,instructor_id,name,status)
                values(?,?,'synthetic','ACTIVE')
                """,
                classroom,
                instructor);
        return child(db, instructor, classroom);
    }

    private Fixture child(JdbcTemplate db, UUID instructor, UUID classroom) {
        UUID child = UUID.randomUUID(), goal = UUID.randomUUID(), activity = UUID.randomUUID();
        UUID scenario = UUID.randomUUID(), session = UUID.randomUUID(), turn = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        db.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        db.update(
                """
                insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at)
                values(?,?,?,'synthetic',?,?)
                """,
                goal,
                child,
                instructor,
                "a".repeat(64),
                now);
        db.update(
                """
                insert into activity(activity_id,child_id,goal_id,scenario_id,status,assigned_at)
                values(?,?,?,?,'IN_PROGRESS',?)
                """,
                activity,
                child,
                goal,
                scenario,
                now);
        db.update(
                """
                insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at)
                values(?,?,?,?,1,'IN_PROGRESS',?)
                """,
                session,
                activity,
                child,
                scenario,
                now);
        db.update(
                """
                insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,
                    turn_number,candidate_id,micro_goal_id,support_level,response_text,canonical_utterance,
                    checkpoint_version,committed_at)
                values(?,?,?,?,?,1,?,?,'S0','synthetic response','synthetic input',1,?)
                """,
                turn,
                session,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                UUID.randomUUID(),
                now);
        db.update(
                """
                update roleplay_session set last_turn_id = ?, last_turn_number = 1, row_version = 1
                where session_id = ?
                """,
                turn,
                session);
        return new Fixture(instructor, classroom, child, goal, activity, session, turn);
    }

    private record Fixture(
            UUID instructor,
            UUID classroom,
            UUID child,
            UUID goal,
            UUID activity,
            UUID session,
            UUID turn) {}
}
