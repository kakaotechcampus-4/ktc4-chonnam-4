package com.neuringo.neuringobe.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@IntegrationTest
class QuizDifficultyMigrationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void upgradesExistingV12ResultsAndKeepsOldServerWritesCompatible() throws Exception {
        String schema = "quiz_difficulty_" + UUID.randomUUID().toString().replace("-", "");
        var pool = (HikariDataSource) dataSource;
        // 별도 연결과 스키마에서 실제 이전 마이그레이션 이력으로 업그레이드한다.
        var upgradeDataSource =
                new DriverManagerDataSource(
                        pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        try {
            flyway(upgradeDataSource, schema, "12").migrate();
            try (var connection = upgradeDataSource.getConnection()) {
                connection.setSchema(schema);
                var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                UUID child = child(db);
                UUID legacyFallback = result(db, child, true);
                UUID legacyNormal = result(db, child, false);
                Map<String, Object> fallbackBefore = snapshot(db, legacyFallback);
                Map<String, Object> normalBefore = snapshot(db, legacyNormal);

                var upgrade = flyway(upgradeDataSource, schema, "24");
                assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(12);
                upgrade.validate();

                Map<String, Object> fallbackAfter = snapshot(db, legacyFallback);
                assertThat(fallbackAfter.remove("difficulty_fallback_reason"))
                        .isEqualTo("LEGACY_UNSPECIFIED");
                assertThat(fallbackAfter).isEqualTo(fallbackBefore);
                Map<String, Object> normalAfter = snapshot(db, legacyNormal);
                assertThat(normalAfter).containsEntry("difficulty_fallback_reason", null);
                normalAfter.remove("difficulty_fallback_reason");
                assertThat(normalAfter).isEqualTo(normalBefore);

                UUID oldServerResult = result(db, child, true);
                assertThat(snapshot(db, oldServerResult))
                        .containsEntry("difficulty_fallback_reason", null)
                        .containsEntry("policy_version", "INITIAL_QUIZ_V1");

                UUID newServerResult = result(db, child, true);
                db.update(
                        """
                        update quiz_result
                        set difficulty_fallback_reason = 'NO_VALID_QUESTIONS',
                            policy_version = 'INITIAL_QUIZ_V2'
                        where activity_id = ?
                        """,
                        newServerResult);
                var allResultsBeforeReplay =
                        db.queryForList("select * from quiz_result order by activity_id");

                assertThat(flyway(upgradeDataSource, schema, "24").migrate().migrationsExecuted)
                        .isZero();
                assertThat(db.queryForList("select * from quiz_result order by activity_id"))
                        .isEqualTo(allResultsBeforeReplay);
            }
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private Flyway flyway(DataSource target, String schema, String version) {
        return Flyway.configure()
                .dataSource(target)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .target(version)
                .load();
    }

    private UUID child(JdbcTemplate db) {
        UUID instructor = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID();
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
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                instructor);
        db.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        return child;
    }

    private UUID result(JdbcTemplate db, UUID child, boolean fallback) {
        UUID activity = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        db.update(
                """
                insert into activity(activity_id,child_id,activity_type,status,assigned_at)
                values(?,?,'SELF_EMOTION_QUIZ','COMPLETED',?)
                """,
                activity,
                child,
                at);
        db.update(
                """
                insert into quiz_result(activity_id,valid_question_count,correct_question_count,
                    overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,
                    initial_support_level,difficulty_fallback_applied,initial_difficulty_used,
                    policy_version,snapshotted_at)
                values(?,?,?,?,?,?,?,?,?,false,'INITIAL_QUIZ_V1',?)
                """,
                activity,
                fallback ? 0 : 3,
                fallback ? 0 : 2,
                fallback ? null : new BigDecimal("0.666667"),
                fallback ? 0 : 1,
                fallback ? 0 : 1,
                fallback ? "L1" : "L2",
                "S1",
                fallback,
                at);
        return activity;
    }

    private Map<String, Object> snapshot(JdbcTemplate db, UUID activity) {
        return new LinkedHashMap<>(
                db.queryForMap("select * from quiz_result where activity_id = ?", activity));
    }
}
