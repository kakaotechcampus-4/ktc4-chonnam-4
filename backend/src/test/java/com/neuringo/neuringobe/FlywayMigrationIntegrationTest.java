package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    @Autowired private QuizItemRepository quizItems;

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
        assertThat(jdbcTemplate.queryForObject(countSeedItems, Integer.class)).isEqualTo(30);

        // 독립 퀴즈가 매번 같은 세트로 나오지 않게, 유형마다 한 활동에 필요한 수(나의 감정 10, 상대 감정 상황 7·그림 3)보다 많이 둔다.
        String countByType =
                "SELECT COUNT(*) FROM quiz_item WHERE item_id::text LIKE '5eed0000-%'"
                        + " AND status = 'APPROVED' AND quiz_type = ?";
        assertThat(
                        jdbcTemplate.queryForObject(
                                countByType, Integer.class, "SELF_EMOTION_SITUATION"))
                .isEqualTo(15);
        assertThat(
                        jdbcTemplate.queryForObject(
                                countByType, Integer.class, "OTHER_EMOTION_SITUATION"))
                .isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(countByType, Integer.class, "OTHER_EMOTION_IMAGE"))
                .isEqualTo(5);

        // 반복 실행 파일은 내용이 바뀔 때마다 다시 돈다. 이미 문항이 있는 DB 에서 다시 돌아도 실패하거나 늘지 않아야 한다.
        String script =
                new ClassPathResource("db/seed/R__quiz_items.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(script);
        assertThat(jdbcTemplate.queryForObject(countSeedItems, Integer.class)).isEqualTo(30);
    }

    @Test
    void everySeedItemCanBeAssignedAndKeepsTheWritingRules() {
        // 배정 조건을 어긴 문항은 오류 없이 배정 후보에서 빠지므로, 문항 수가 모자란 것처럼 보이기 전에 여기서 잡는다.
        List<QuizItem> seeds =
                quizItems.findAll().stream()
                        .filter(item -> item.getItemId().toString().startsWith("5eed0000-"))
                        .toList();
        Map<Emotion, String> labels =
                Map.of(
                        Emotion.JOY, "기쁨",
                        Emotion.SADNESS, "슬픔",
                        Emotion.ANGER, "화남",
                        Emotion.SURPRISE, "놀람",
                        Emotion.UPSET, "속상함");

        // 승인 30문항에 DRAFT 로 내린 예시 2문항을 더한 수다.
        assertThat(seeds).hasSize(32);
        for (QuizItem item : seeds) {
            assertThatCode(item::validateForAssignment)
                    .as(item.getItemId().toString())
                    .doesNotThrowAnyException();
            assertThat(item.getChoices())
                    .as(item.getItemId().toString())
                    .hasSize(3)
                    .doesNotHaveDuplicates();
            assertThat(item.getCorrectAnswer())
                    .as(item.getItemId().toString())
                    .isEqualTo(labels.get(item.getEmotion()));
            assertThat(item.getChoices().containsAll(List.of("슬픔", "속상함")))
                    .as(item.getItemId() + " 슬픔과 속상함을 한 문항의 보기에 같이 넣지 않는다")
                    .isFalse();
        }
    }
}
