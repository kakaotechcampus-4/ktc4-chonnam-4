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

/**
 * V12 까지는 서버가 활동을 완료로 바꾸지 않았다. V13 을 거치면 결과가 있는 독립 퀴즈만 결과 저장 시각으로 완료·스탬프가 맞춰지고 학습 기록이 하나 생기는지 본다.
 */
@IntegrationTest
class LearningRecordBackfillMigrationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void completesIndependentQuizzesWithResultsAndLeavesTheRestAlone() throws Exception {
        String schema = "learning_record_" + UUID.randomUUID().toString().replace("-", "");
        var pool = (HikariDataSource) dataSource;
        // 스키마를 바꾼 연결이 앱 풀로 돌아가지 않게 따로 연다.
        var upgradeDataSource =
                new DriverManagerDataSource(
                        pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        try {
            migrate(upgradeDataSource, schema, "12");
            try (var connection = upgradeDataSource.getConnection()) {
                connection.setSchema(schema);
                var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                Instant solvedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                UUID child = child(db, classroom(db, instructor(db)));

                UUID solved = activity(db, child, "OTHER_EMOTION_QUIZ", null);
                quizResult(db, solved, solvedAt);
                UUID unsolved = activity(db, child, "SELF_EMOTION_QUIZ", null);
                UUID firstCombined =
                        activity(db, child, "FIRST_COMBINED", goal(db, child, instructor(db)));
                quizResult(db, firstCombined, solvedAt);

                MigrateResult migration = migrate(upgradeDataSource, schema, "13");

                assertThat(migration.migrationsExecuted).isEqualTo(1);
                assertThat(status(db, solved)).isEqualTo("COMPLETED");
                assertThat(time(db, "completed_at", solved)).isEqualTo(solvedAt);
                assertThat(time(db, "reward_issued_at", solved)).isEqualTo(solvedAt);
                assertThat(records(db, solved)).isOne();

                assertThat(status(db, unsolved)).isEqualTo("IN_PROGRESS");
                assertThat(records(db, unsolved)).isZero();
                assertThat(status(db, firstCombined)).isEqualTo("IN_PROGRESS");
                assertThat(time(db, "reward_issued_at", firstCombined)).isNull();
                assertThat(records(db, firstCombined)).isZero();
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

    private String status(JdbcTemplate db, UUID activity) {
        return db.queryForObject(
                "select status from activity where activity_id = ?", String.class, activity);
    }

    private Instant time(JdbcTemplate db, String column, UUID activity) {
        Timestamp value =
                db.queryForObject(
                        "select " + column + " from activity where activity_id = ?",
                        Timestamp.class,
                        activity);
        return value == null ? null : value.toInstant();
    }

    private long records(JdbcTemplate db, UUID activity) {
        return db.queryForObject(
                "select count(*) from learning_record where activity_id = ?", Long.class, activity);
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

    private UUID activity(JdbcTemplate db, UUID child, String type, UUID goal) {
        UUID id = UUID.randomUUID();
        db.update(
                """
                insert into activity(activity_id,child_id,goal_id,activity_type,status,assigned_at,started_at)
                values(?,?,?,?,'IN_PROGRESS',?,?)
                """,
                id,
                child,
                goal,
                type,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return id;
    }

    private void quizResult(JdbcTemplate db, UUID activity, Instant at) {
        db.update(
                """
                insert into quiz_result(activity_id,valid_question_count,correct_question_count,total_hint_count,
                    resolved_after_hint_count,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at)
                values(?,1,1,0,0,false,false,'synthetic',?)
                """,
                activity,
                Timestamp.from(at));
    }
}
