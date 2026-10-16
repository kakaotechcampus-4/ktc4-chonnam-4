package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIntegrationTest {

    @Autowired private Flyway flyway;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void appliesMigrationAndDoesNotReapplyIt() {
        MigrationInfo smokeTestMigration =
                Arrays.stream(flyway.info().applied())
                        .filter(migration -> "9999".equals(migration.getVersion().getVersion()))
                        .findFirst()
                        .orElse(null);

        assertThat(smokeTestMigration).isNotNull();
        assertThat(smokeTestMigration.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM flyway_smoke_test WHERE id = 1",
                                Integer.class))
                .isEqualTo(1);

        MigrateResult secondMigration = flyway.migrate();

        assertThat(secondMigration.migrationsExecuted).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM flyway_smoke_test WHERE id = 1",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void quizItemSeedRunsFromItsOwnFolderAndCanRunAgain() throws Exception {
        MigrationInfo seed =
                Arrays.stream(flyway.info().applied())
                        .filter(migration -> migration.getVersion() == null)
                        .filter(migration -> migration.getScript().equals("R__quiz_items.sql"))
                        .findFirst()
                        .orElse(null);
        assertThat(seed).isNotNull();
        assertThat(seed.getState()).isEqualTo(MigrationState.SUCCESS);

        String countSeedItems =
                "SELECT COUNT(*) FROM quiz_item WHERE item_id::text LIKE '5eed0000-%'"
                        + " AND status = 'APPROVED'";
        assertThat(jdbcTemplate.queryForObject(countSeedItems, Integer.class)).isEqualTo(6);

        // 반복 실행 파일은 내용이 바뀔 때마다 다시 돈다. 이미 문항이 있는 DB 에서 다시 돌아도 실패하거나 늘지 않아야 한다.
        String script =
                new ClassPathResource("db/seed/R__quiz_items.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(script);
        assertThat(jdbcTemplate.queryForObject(countSeedItems, Integer.class)).isEqualTo(6);
    }
}
