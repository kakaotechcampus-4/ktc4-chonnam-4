package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.roleplay.config.RoleplaySpeechDeliveryConfiguration;
import com.neuringo.neuringobe.roleplay.infrastructure.speech.NoAudioRoleplaySpeechDelivery;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class RoleplaySpeechDeliveryConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RoleplaySpeechDeliveryConfiguration.class);

    @Test
    void disabledHttpDoesNotRegisterDeliveryEvenWhenNoneIsSelected() {
        runner.withPropertyValues("roleplay.speech.delivery=none")
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(RoleplaySpeechDelivery.class));
    }

    @Test
    void explicitNoneRegistersActualDeliveryAndResponseMapper() {
        enabled()
                .withPropertyValues("roleplay.speech.delivery=none")
                .withUserConfiguration(RequiresDelivery.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(RoleplaySpeechDelivery.class)
                                    .hasSingleBean(RoleplayTurnResponseMapper.class);
                            var delivery = context.getBean(RoleplaySpeechDelivery.class);
                            assertThat(delivery).isInstanceOf(NoAudioRoleplaySpeechDelivery.class);
                            assertThat(
                                            delivery.publish(
                                                    UUID.randomUUID(),
                                                    new SynthesizedSpeech(
                                                            new byte[] {1, 2, 3},
                                                            AudioFormat.MP3,
                                                            "test",
                                                            "test",
                                                            "test")))
                                    .isEmpty();
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown"})
    void blankOrUnsupportedModeDoesNotHideMissingDelivery(String mode) {
        enabled()
                .withPropertyValues("roleplay.speech.delivery=" + mode)
                .withUserConfiguration(RequiresDelivery.class)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseInstanceOf(
                                                org.springframework.beans.factory
                                                        .NoSuchBeanDefinitionException.class));
    }

    @Test
    void missingModeDoesNotHideMissingDelivery() {
        enabled()
                .withUserConfiguration(RequiresDelivery.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void existingDeliveryIsNotReplacedWhenNoneIsSelected() {
        RoleplaySpeechDelivery replacement = (id, speech) -> Optional.of("/test/protected-audio");
        enabled()
                .withPropertyValues("roleplay.speech.delivery=none")
                .withBean(RoleplaySpeechDelivery.class, () -> replacement)
                .withUserConfiguration(RequiresDelivery.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(RoleplaySpeechDelivery.class);
                            assertThat(context.getBean(RoleplaySpeechDelivery.class))
                                    .isSameAs(replacement);
                        });
    }

    @Test
    void deploymentEnvironmentSelectsNoneThroughApplicationYaml() {
        runner.withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new SystemEnvironmentPropertySource(
                                                        "deployment-test",
                                                        Map.of(
                                                                "ROLEPLAY_HTTP_ENABLED",
                                                                "true",
                                                                "ROLEPLAY_SPEECH_DELIVERY",
                                                                "none"))))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RequiresDelivery.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(RoleplaySpeechDelivery.class);
                            assertThat(context.getBean(RoleplaySpeechDelivery.class))
                                    .isInstanceOf(NoAudioRoleplaySpeechDelivery.class);
                        });
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("roleplay.http.enabled=true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequiresDelivery {
        @Bean
        RoleplayTurnResponseMapper responses(RoleplaySpeechDelivery delivery) {
            return new RoleplayTurnResponseMapper(delivery, false);
        }
    }
}
