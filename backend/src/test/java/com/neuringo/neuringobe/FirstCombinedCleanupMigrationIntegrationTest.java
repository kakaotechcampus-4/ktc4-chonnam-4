package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** V11 까지 쌓인 최초 결합 중복이 V12 를 거치면 아동별로 가장 먼저 배정된 하나만 남는지 본다. */
@IntegrationTest
class FirstCombinedCleanupMigrationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void keepsEarliestFirstCombinedPerChildAndDeletesTheRestWithTheirRecords() throws Exception {
        String schema = "first_combined_" + UUID.randomUUID().toString().replace("-", "");
        var pool = (HikariDataSource) dataSource;
        // 스키마를 바꾼 연결이 앱 풀로 돌아가지 않게 따로 연다.
        var upgradeDataSource =
                new DriverManagerDataSource(
                        pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        try {
            migrate(upgradeDataSource, schema, "11");
            try (var connection = upgradeDataSource.getConnection()) {
                connection.setSchema(schema);
                var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                UUID instructor = instructor(db);
                UUID classroom = classroom(db, instructor);

                UUID child = child(db, classroom);
                UUID keptGoal = goal(db, child, instructor);
                UUID kept = activity(db, child, keptGoal, base.minusSeconds(60));
                UUID keptHint = UUID.randomUUID();
                UUID keptQuiz = quizWithAttempt(db, kept, keptHint);
                UUID extraGoal = goal(db, child, instructor);
                UUID extra = activity(db, child, extraGoal, base);
                UUID extraHint = UUID.randomUUID();
                UUID extraQuiz = quizWithAttempt(db, extra, extraHint);
                quizResult(db, extra, base);
                UUID extraSession = roleplay(db, extra, child, base);

                UUID onlyChild = child(db, classroom);
                UUID only =
                        activity(
                                db,
                                onlyChild,
                                goal(db, onlyChild, instructor),
                                base.minusSeconds(30));

                var migration = migrate(upgradeDataSource, schema, "12");

                assertThat(migration.migrationsExecuted).isEqualTo(1);
                assertThat(count(db, "activity", "activity_id", kept)).isOne();
                assertThat(count(db, "activity_quiz", "activity_quiz_id", keptQuiz)).isOne();
                assertThat(count(db, "quiz_attempt", "activity_quiz_id", keptQuiz)).isOne();
                assertThat(count(db, "activity", "activity_id", only)).isOne();

                assertThat(count(db, "activity", "activity_id", extra)).isZero();
                assertThat(count(db, "activity_quiz", "activity_quiz_id", extraQuiz)).isZero();
                assertThat(count(db, "quiz_attempt", "activity_quiz_id", extraQuiz)).isZero();
                assertThat(count(db, "quiz_result", "activity_id", extra)).isZero();
                assertThat(count(db, "roleplay_session", "session_id", extraSession)).isZero();
                assertThat(count(db, "roleplay_turn", "session_id", extraSession)).isZero();
                // 목표는 활동과 따로 남는다.
                assertThat(count(db, "learning_goal", "goal_id", extraGoal)).isOne();
                assertThat(count(db, "quiz_hint", "idempotency_key", extraHint)).isZero();
                assertThat(count(db, "quiz_hint", "idempotency_key", keptHint)).isOne();
            }
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private MigrateResult migrate(DataSource target, String schema, String version) {
        return Flyway.configure()
                .dataSource(target)
                .schemas(schema)
                .defaultSchema(schema)
                .target(version)
                .load()
                .migrate();
    }

    private long count(JdbcTemplate db, String table, String column, UUID id) {
        return db.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Long.class, id);
    }

    private UUID instructor(JdbcTemplate db) {
        UUID id = UUID.randomUUID();
        db.update(
                """
                insert into user_account(user_id,email,password_hash,name,role,status,created_at)
                values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)
                """,
                id,
                id + "@example.com",
                "test-hash",
                Timestamp.from(Instant.now()));
        return id;
    }

    private UUID classroom(JdbcTemplate db, UUID instructor) {
        UUID id = UUID.randomUUID();
        db.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                id,
                instructor);
        return id;
    }

    private UUID child(JdbcTemplate db, UUID classroom) {
        UUID id = UUID.randomUUID();
        db.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                id,
                classroom);
        return id;
    }

    private UUID goal(JdbcTemplate db, UUID child, UUID instructor) {
        UUID id = UUID.randomUUID();
        db.update(
                """
                insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at)
                values(?,?,?,'synthetic',?,?)
                """,
                id,
                child,
                instructor,
                "a".repeat(64),
                Timestamp.from(Instant.now()));
        return id;
    }

    private UUID activity(JdbcTemplate db, UUID child, UUID goal, Instant assignedAt) {
        UUID id = UUID.randomUUID();
        db.update(
                """
                insert into activity(activity_id,child_id,goal_id,activity_type,status,assigned_at)
                values(?,?,?,'FIRST_COMBINED','COMPLETED',?)
                """,
                id,
                child,
                goal,
                Timestamp.from(assignedAt));
        return id;
    }

    /** 시드 문항(V6) 하나를 붙이고 힌트까지 받은 풀이를 남긴다. */
    private UUID quizWithAttempt(JdbcTemplate db, UUID activity, UUID hintKey) {
        UUID activityQuiz = UUID.randomUUID(), attempt = UUID.randomUUID();
        db.update(
                """
                insert into activity_quiz(activity_quiz_id,activity_id,item_id,item_version,question_order)
                values(?,?,'5eed0000-0000-4000-8000-000000000001',1,1)
                """,
                activityQuiz,
                activity);
        db.update(
                """
                insert into quiz_attempt(attempt_id,activity_quiz_id,first_response_correct,status,responded_at)
                values(?,?,false,'FINALIZED',?)
                """,
                attempt,
                activityQuiz,
                Timestamp.from(Instant.now()));
        db.update(
                """
                insert into quiz_hint(hint_id,attempt_id,idempotency_key,hint_order,hint_type,hint_text,issued_at)
                values(?,?,?,1,'OBSERVABLE_BODY_CUE','synthetic hint',?)
                """,
                UUID.randomUUID(),
                attempt,
                hintKey,
                Timestamp.from(Instant.now()));
        return activityQuiz;
    }

    private void quizResult(JdbcTemplate db, UUID activity, Instant at) {
        db.update(
                """
                insert into quiz_result(activity_id,valid_question_count,correct_question_count,total_hint_count,
                    resolved_after_hint_count,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at)
                values(?,1,0,1,0,false,false,'synthetic',?)
                """,
                activity,
                Timestamp.from(at));
    }

    /** 마지막 턴을 가리키는 세션까지 만든다. 지우는 순서가 틀리면 커밋 때 FK 가 걸린다. */
    private UUID roleplay(JdbcTemplate db, UUID activity, UUID child, Instant at) {
        UUID session = UUID.randomUUID(), turn = UUID.randomUUID();
        db.update(
                """
                insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at)
                values(?,?,?,?,1,'COMPLETED',?)
                """,
                session,
                activity,
                child,
                UUID.randomUUID(),
                Timestamp.from(at));
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
                Timestamp.from(at));
        db.update(
                "update roleplay_session set last_turn_id = ?, last_turn_number = 1 where session_id = ?",
                turn,
                session);
        return session;
    }
}
