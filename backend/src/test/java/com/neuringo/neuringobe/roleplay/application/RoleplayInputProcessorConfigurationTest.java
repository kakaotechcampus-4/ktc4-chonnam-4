package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.infrastructure.input.ModelRoleplayInputProcessor;
import com.neuringo.neuringobe.roleplay.config.RoleplayInputProcessorConfiguration;
import com.neuringo.neuringobe.roleplay.service.RoleplayLearnerPrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class RoleplayInputProcessorConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RoleplayInputProcessorConfiguration.class);

    @Test
    void disabledHttpDoesNotCreateProcessorOrRequireProvider() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RoleplayInputProcessor.class);
                });
    }

    @Test
    void enabledHttpRegistersActualProcessorWithoutTestImplementation() {
        runner.withPropertyValues("roleplay.http.enabled=true")
                .withBean(LlmProvider.class, () -> mock(LlmProvider.class))
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(
                        RoleplayLearnerPrivacyService.class,
                        () -> mock(RoleplayLearnerPrivacyService.class))
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(RoleplayInputProcessor.class);
                            assertThat(context.getBean(RoleplayInputProcessor.class))
                                    .isInstanceOf(ModelRoleplayInputProcessor.class);
                        });
    }

    @Test
    void enabledHttpCannotSilentlyUsePassThroughWhenProviderMissing() {
        runner.withPropertyValues("roleplay.http.enabled=true")
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(
                        RoleplayLearnerPrivacyService.class,
                        () -> mock(RoleplayLearnerPrivacyService.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void explicitReplacementRemainsPossibleWithoutCreatingSecondProcessor() {
        RoleplayInputProcessor replacement = text -> new RoleplayInputProcessor.Unavailable();
        runner.withPropertyValues("roleplay.http.enabled=true")
                .withBean(RoleplayInputProcessor.class, () -> replacement)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(RoleplayInputProcessor.class);
                            assertThat(context.getBean(RoleplayInputProcessor.class))
                                    .isSameAs(replacement);
                        });
    }
}
