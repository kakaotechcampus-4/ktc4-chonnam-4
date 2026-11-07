package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@IntegrationTest
@org.junit.jupiter.api.extension.ExtendWith(ScenarioPoolTestIsolation.class)
class ScenarioApprovalMigrationIntegrationTest {
    @Autowired private PostgreSQLContainer postgres;

    @Test
    void upgradesExistingApprovedScenarioWithoutInventingItsInstructorOrConditions() {
        String schema = "approval_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        UUID scenario = UUID.randomUUID();
        try {
            migration(dataSource, schema, "32").migrate();
            jdbc.update(
                    "INSERT INTO "
                            + schema
                            + ".scenario"
                            + " (scenario_id, status, scenario_level, title, scenario_facts, origin, created_at)"
                            + " VALUES (?, 'APPROVED', 'L2', '기존 승인본', '[]', 'PREPARED', now())",
                    scenario);

            assertThat(migration(dataSource, schema, "33").migrate().migrationsExecuted)
                    .isEqualTo(1);

            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM " + schema + ".scenario_approval",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM "
                                            + schema
                                            + ".scenario WHERE scenario_id = ?",
                                    String.class,
                                    scenario))
                    .isEqualTo("APPROVED");
            assertThat(migration(dataSource, schema, "33").migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void upgradesV38ProvenanceAndPreservesApprovedContentAfterDeletion() throws Exception {
        String schema = "shared_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        UUID instructor = UUID.randomUUID();
        UUID classroom = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();
        try {
            migration(dataSource, schema, "38").migrate();
            try (var connection = dataSource.getConnection()) {
                connection.setSchema(schema);
                var scoped =
                        new JdbcTemplate(
                                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                                        connection, true));
                scoped.update(
                        "INSERT INTO user_account (user_id,email,password_hash,name,role,status,created_at) VALUES (?,?,'test-hash','검사 강사','INSTRUCTOR','ACTIVE',now())",
                        instructor,
                        instructor + "@example.com");
                scoped.update(
                        "INSERT INTO classroom (class_id,instructor_id,name) VALUES (?,?,'검사 학급')",
                        classroom,
                        instructor);
                scoped.update(
                        "INSERT INTO scenario (scenario_id,class_id,status,scenario_level,title,scenario_facts,origin,created_at) VALUES (?,?,'APPROVED','L2','기존 공유 승인본','[]','PREPARED',now())",
                        scenario,
                        classroom);
                scoped.update(
                        "INSERT INTO scenario_approval (scenario_id,instructor_id,goal_title,scenario_level,verification_rule_version,approved_at) VALUES (?,?,'기존 확인 조건','L2',1,now())",
                        scenario,
                        instructor);
                assertThat(migration(dataSource, schema, "39").migrate().migrationsExecuted)
                        .isEqualTo(1);
                scoped.update("DELETE FROM classroom WHERE class_id=?", classroom);
                scoped.update("DELETE FROM user_account WHERE user_id=?", instructor);
                assertThat(
                                scoped.queryForMap(
                                        "SELECT title,class_id FROM scenario WHERE scenario_id=?",
                                        scenario))
                        .containsEntry("title", "기존 공유 승인본")
                        .containsEntry("class_id", null);
                assertThat(
                                scoped.queryForMap(
                                        "SELECT goal_title,instructor_id FROM scenario_approval WHERE scenario_id=?",
                                        scenario))
                        .containsEntry("goal_title", "기존 확인 조건")
                        .containsEntry("instructor_id", null);
                assertThat(migration(dataSource, schema, "39").migrate().migrationsExecuted)
                        .isZero();
            }
        } finally {
            new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway migration(DriverManagerDataSource dataSource, String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
