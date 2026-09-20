package com.neuringo.neuringobe;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesMigrationAndDoesNotReapplyIt() {
        MigrationInfo smokeTestMigration = Arrays.stream(flyway.info().applied())
                .filter(migration -> "9999".equals(migration.getVersion().getVersion()))
                .findFirst()
                .orElse(null);

        assertThat(smokeTestMigration).isNotNull();
        assertThat(smokeTestMigration.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_smoke_test WHERE id = 1",
                Integer.class
        )).isEqualTo(1);

        MigrateResult secondMigration = flyway.migrate();

        assertThat(secondMigration.migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_smoke_test WHERE id = 1",
                Integer.class
        )).isEqualTo(1);
    }
}
