package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.config.RoleplayProgressConfiguration;
import com.neuringo.neuringobe.roleplay.config.RoleplayProgressProperties;
import com.neuringo.neuringobe.roleplay.service.JdbcRoleplayProgressPolicy;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class RoleplayProgressConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RoleplayProgressConfiguration.class);

    @Test
    void absentLimitsDoNotInventAPolicyOrReadTheDatabase() {
        runner.run(
                context -> {
                    var limits = context.getBean(RoleplayProgressProperties.class);
                    assertThat(limits.configured()).isFalse();
                    assertThat(limits.maximumTurnCount()).isNull();
                    assertThat(limits.maximumSupportTurnsPerMicroGoal()).isNull();
                    var jdbc = mock(JdbcTemplate.class);
                    var policy = new JdbcRoleplayProgressPolicy(jdbc, limits);
                    UUID id = UUID.randomUUID();
                    var key = new RoleplayContextSource.Key(id, id, id, id, id, 1, 0);
                    assertThat(
                                    policy.select(
                                            new RoleplayProgressPolicy.Stored(
                                                    key, id, "S2", 1, Set.of(id)),
                                            RoleplayTurnDeadline.start()))
                            .isEmpty();
                    verifyNoInteractions(jdbc);
                });
    }

    @Test
    void bindsBothExplicitLimitsWithoutChangingThem() {
        runner.withPropertyValues(
                        "roleplay.progress.maximum-turn-count=17",
                        "roleplay.progress.maximum-support-turns-per-micro-goal=3")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var limits = context.getBean(RoleplayProgressProperties.class);
                            assertThat(limits.configured()).isTrue();
                            assertThat(limits.maximumTurnCount()).isEqualTo(17);
                            assertThat(limits.maximumSupportTurnsPerMicroGoal()).isEqualTo(3);
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"totalOnly", "goalOnly", "zero", "negative"})
    void rejectsPartialOrInvalidLimitsAtStartup(String invalid) {
        String[] values =
                switch (invalid) {
                    case "totalOnly" -> new String[] {"roleplay.progress.maximum-turn-count=17"};
                    case "goalOnly" ->
                            new String[] {
                                "roleplay.progress.maximum-support-turns-per-micro-goal=3"
                            };
                    case "zero" ->
                            new String[] {
                                "roleplay.progress.maximum-turn-count=0",
                                "roleplay.progress.maximum-support-turns-per-micro-goal=3"
                            };
                    default ->
                            new String[] {
                                "roleplay.progress.maximum-turn-count=17",
                                "roleplay.progress.maximum-support-turns-per-micro-goal=-1"
                            };
                };
        runner.withPropertyValues(values)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
                        });
    }
}
