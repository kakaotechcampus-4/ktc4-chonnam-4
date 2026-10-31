package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 기존 턴은 추측해 채우지 않고, V17·V18의 기록 보존과 코드·누락 제약을 확인한다. */
@IntegrationTest
class RoleplayResponseMetadataMigrationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void upgradePreservesUnknownLegacyMetadataAndRejectsPartialOrUnknownCodes() throws Exception {
        String schema = "response_metadata_" + UUID.randomUUID().toString().replace("-", "");
        var pool = (HikariDataSource) dataSource;
        var target =
                new DriverManagerDataSource(
                        pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        try {
            migrate(target, schema, "16");
            try (var connection = target.getConnection()) {
                connection.setSchema(schema);
                var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                UUID turn = legacyTurn(db);
                var before = db.queryForMap("select * from roleplay_turn where turn_id=?", turn);
                assertThat(migrate(target, schema, "17")).isEqualTo(1);
                var after = db.queryForMap("select * from roleplay_turn where turn_id=?", turn);
                assertThat(after)
                        .containsAllEntriesOf(before)
                        .containsEntry("response_type", null)
                        .containsEntry("strategy_used", null);
                assertThatThrownBy(
                                () ->
                                        db.update(
                                                "update roleplay_turn set response_type='ANSWER_REVEAL' where turn_id=?",
                                                turn))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(
                                () ->
                                        db.update(
                                                "update roleplay_turn set response_type='UNKNOWN', strategy_used='PROBE_EVENT' where turn_id=?",
                                                turn))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(
                                () ->
                                        db.update(
                                                "update roleplay_turn set response_type='GUIDING_QUESTION', strategy_used='UNKNOWN' where turn_id=?",
                                                turn))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void v18KeepsOldInputContextUnknownAndChecksTheWholeGroup() throws Exception {
        String schema = "input_context_" + UUID.randomUUID().toString().replace("-", "");
        var pool = (HikariDataSource) dataSource;
        var target =
                new DriverManagerDataSource(
                        pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        try {
            migrate(target, schema, "17");
            try (var connection = target.getConnection()) {
                connection.setSchema(schema);
                var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                var turn = legacyTurn(db);
                var before = db.queryForMap("select * from roleplay_turn where turn_id=?", turn);
                assertThat(migrate(target, schema, "18")).isEqualTo(1);
                assertThat(db.queryForMap("select * from roleplay_turn where turn_id=?", turn))
                        .containsAllEntriesOf(before)
                        .containsEntry("input_micro_goal_id", null)
                        .containsEntry("input_support_level", null)
                        .containsEntry("input_support_turn", null)
                        .containsEntry("input_prompt_count", null)
                        .containsEntry("input_answer_revealed", null);
                assertThatThrownBy(
                                () ->
                                        db.update(
                                                "update roleplay_turn set input_micro_goal_id=? where turn_id=?",
                                                UUID.randomUUID(),
                                                turn))
                        .isInstanceOf(DataIntegrityViolationException.class);
                var goal = UUID.randomUUID();
                db.update(
                        "update roleplay_turn set input_micro_goal_id=?, input_support_level='S1', input_support_turn=1, input_prompt_count=0, input_answer_revealed=false where turn_id=?",
                        goal,
                        turn);
                for (var invalid :
                        java.util.List.of(
                                "input_support_level='S9'",
                                "input_support_turn=0",
                                "input_prompt_count=-1",
                                "input_answer_revealed=null")) {
                    assertThatThrownBy(
                                    () ->
                                            db.update(
                                                    "update roleplay_turn set "
                                                            + invalid
                                                            + " where turn_id=?",
                                                    turn))
                            .isInstanceOf(DataIntegrityViolationException.class);
                }
                assertThat(
                                db.queryForMap(
                                        "select input_micro_goal_id, input_answer_revealed from roleplay_turn where turn_id=?",
                                        turn))
                        .containsEntry("input_micro_goal_id", goal)
                        .containsEntry("input_answer_revealed", false);
            }
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private int migrate(DataSource target, String schema, String version) {
        return Flyway.configure()
                .dataSource(target)
                .schemas(schema)
                .defaultSchema(schema)
                .target(version)
                .load()
                .migrate()
                .migrationsExecuted;
    }

    private UUID legacyTurn(JdbcTemplate db) {
        UUID owner = UUID.randomUUID(), classroom = UUID.randomUUID(), child = UUID.randomUUID();
        UUID goal = UUID.randomUUID(), activity = UUID.randomUUID(), session = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        db.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,'test-hash','synthetic','INSTRUCTOR','ACTIVE',now())",
                owner,
                owner + "@example.com");
        db.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                owner);
        db.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        db.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,now())",
                goal,
                child,
                owner,
                "a".repeat(64));
        db.update(
                "insert into activity(activity_id,child_id,goal_id,activity_type,status,assigned_at) values(?,?,?,'ROLEPLAY','IN_PROGRESS',now())",
                activity,
                child,
                goal);
        db.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',now())",
                session,
                activity,
                child,
                UUID.randomUUID());
        db.update(
                """
                insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,
                    turn_number,candidate_id,micro_goal_id,support_level,response_text,canonical_utterance,
                    checkpoint_version,committed_at)
                values(?,?,?,?,?,1,?,?,'S3','synthetic question?','synthetic input',1,now())
                """,
                turn,
                session,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                UUID.randomUUID());
        db.update(
                "update roleplay_session set last_turn_number=1, last_turn_id=?, row_version=1 where session_id=?",
                turn,
                session);
        return turn;
    }
}
