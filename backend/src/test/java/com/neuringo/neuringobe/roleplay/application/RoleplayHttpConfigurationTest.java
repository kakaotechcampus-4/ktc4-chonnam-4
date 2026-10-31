package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.port.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.config.RoleplayHttpConfiguration;
import com.neuringo.neuringobe.roleplay.config.RoleplayHttpProperties;
import com.neuringo.neuringobe.roleplay.config.RoleplayProgressConfiguration;
import com.neuringo.neuringobe.roleplay.config.RoleplayProgressProperties;
import com.neuringo.neuringobe.roleplay.controller.RoleplayVoiceController;
import com.neuringo.neuringobe.roleplay.service.*;
import jakarta.servlet.MultipartConfigElement;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.json.JsonMapper;

class RoleplayHttpConfigurationTest {
    @TempDir Path directory;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        RoleplayHttpConfiguration.class,
                        RoleplayProgressConfiguration.class,
                        RoleplayVoiceController.class);
    }

    private ApplicationContextRunner enabled() {
        return runner().withPropertyValues(
                        "roleplay.http.enabled=true",
                        "roleplay.progress.maximum-turn-count=77",
                        "roleplay.progress.maximum-support-turns-per-micro-goal=9",
                        "roleplay.http.spool-directory=" + directory,
                        "roleplay.http.maximum-upload-bytes=16",
                        "roleplay.http.maximum-request-bytes=65536",
                        "roleplay.http.allowed-formats=WEBM",
                        "roleplay.http.worker-count=1",
                        "roleplay.http.queue-capacity=0");
    }

    private ApplicationContextRunner ports(ApplicationContextRunner runner) {
        return portsWithoutGoals(runner)
                .withBean(
                        RoleplayActivityGoalEvaluator.class,
                        () -> mock(RoleplayActivityGoalEvaluator.class));
    }

    private ApplicationContextRunner portsWithoutGoals(ApplicationContextRunner runner) {
        return runner.withBean(
                        RoleplayRequestScopeService.class,
                        () -> mock(RoleplayRequestScopeService.class))
                .withBean(RoleplayCheckpointStore.class, () -> mock(RoleplayCheckpointStore.class))
                .withBean(RoleplayRequestGuard.class, () -> mock(RoleplayRequestGuard.class))
                .withBean(RoleplayContextSource.class, () -> mock(RoleplayContextSource.class))
                .withBean(
                        RoleplayCompletionService.class,
                        () -> mock(RoleplayCompletionService.class))
                .withBean(RoleplayInputProcessor.class, () -> mock(RoleplayInputProcessor.class))
                .withBean(LlmProvider.class, () -> mock(LlmProvider.class))
                .withBean(SpeechToTextProvider.class, () -> mock(SpeechToTextProvider.class))
                .withBean(TextToSpeechProvider.class, () -> mock(TextToSpeechProvider.class))
                .withBean(RoleplaySpeechDelivery.class, () -> mock(RoleplaySpeechDelivery.class))
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build());
    }

    private ApplicationContextRunner deploymentEnvironment(Map<String, Object> values) {
        return runner().withInitializer(
                        c ->
                                c.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new SystemEnvironmentPropertySource(
                                                        "deployment-test", values)))
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    private Map<String, Object> deploymentValues() {
        return Map.of(
                "ROLEPLAY_HTTP_ENABLED", "true",
                "ROLEPLAY_SPOOL_DIRECTORY", directory.toString(),
                "ROLEPLAY_UPLOAD_MAX_BYTES", "16",
                "ROLEPLAY_REQUEST_MAX_BYTES", "65536",
                "ROLEPLAY_ALLOWED_FORMATS", "WEBM,WAV",
                "ROLEPLAY_WORKER_COUNT", "2",
                "ROLEPLAY_QUEUE_CAPACITY", "3",
                "ROLEPLAY_TURN_BUDGET", "45s",
                "ROLEPLAY_MAXIMUM_TURN_COUNT", "77",
                "ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL", "9");
    }

    @Test
    void applicationYamlReadsDeployedEnvironmentVariables() {
        ports(deploymentEnvironment(deploymentValues()))
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var http = c.getBean(RoleplayHttpProperties.class);
                            assertThat(http.spoolDirectory()).isEqualTo(directory);
                            assertThat(http.maximumUploadBytes()).isEqualTo(16);
                            assertThat(http.maximumRequestBytes()).isEqualTo(65536);
                            assertThat(http.allowedFormats())
                                    .containsExactlyInAnyOrder(AudioFormat.WEBM, AudioFormat.WAV);
                            assertThat(http.workerCount()).isEqualTo(2);
                            assertThat(http.queueCapacity()).isEqualTo(3);
                            assertThat(http.turnBudget()).isEqualTo(Duration.ofSeconds(45));
                            assertThat(c.getBean(RoleplayProgressProperties.class))
                                    .isEqualTo(new RoleplayProgressProperties(77, 9));
                            assertThat(c).hasSingleBean(RoleplayVoiceInputService.class);
                        });
    }

    @Test
    void applicationYamlKeepsUnconfiguredDeploymentDisabled() {
        deploymentEnvironment(Map.of())
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            assertThat(c).doesNotHaveBean(RoleplayVoiceInputService.class);
                            assertThat(c.getBean(RoleplayProgressProperties.class).configured())
                                    .isFalse();
                        });
    }

    @Test
    void deployedFlagCannotEnableApiWithoutCapacityPolicy() {
        var values = new HashMap<>(deploymentValues());
        values.remove("ROLEPLAY_UPLOAD_MAX_BYTES");
        ports(deploymentEnvironment(values)).run(c -> assertThat(c).hasFailed());
    }

    @Test
    void deployedFlagCannotEnableApiWithoutProgressPolicy() {
        var values = new HashMap<>(deploymentValues());
        values.remove("ROLEPLAY_MAXIMUM_TURN_COUNT");
        values.remove("ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL");
        ports(deploymentEnvironment(values))
                .run(
                        c -> {
                            assertThat(c).hasFailed();
                            assertThat(c.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "역할극 HTTP API를 켜려면 전체·목표별 턴 제한을 함께 설정해야 합니다.");
                        });
    }

    @Test
    void disabledByDefaultNeedsNoPortsAndRegistersNoEndpoint() {
        runner().run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            assertThat(c).doesNotHaveBean(RoleplayVoiceInputService.class);
                            assertThat(c).doesNotHaveBean(RoleplayVoiceController.class);
                        });
    }

    @Test
    void explicitConfigurationBuildsServiceWorkersAndMultipartLimits() {
        ports(enabled())
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            assertThat(c).hasSingleBean(RoleplayVoiceInputService.class);
                            assertThat(c).hasSingleBean(RoleplayVoiceController.class);
                            var multipart = c.getBean(MultipartConfigElement.class);
                            assertThat(multipart.getMaxFileSize()).isEqualTo(16);
                            assertThat(multipart.getMaxRequestSize()).isEqualTo(65536);
                        });
    }

    @Test
    void enabledWithoutTurnLimitsFailsAtStartup() {
        ports(
                        enabled()
                                .withPropertyValues(
                                        "roleplay.progress.maximum-turn-count=",
                                        "roleplay.progress.maximum-support-turns-per-micro-goal="))
                .run(
                        c -> {
                            assertThat(c).hasFailed();
                            assertThat(c.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "역할극 HTTP API를 켜려면 전체·목표별 턴 제한을 함께 설정해야 합니다.");
                        });
    }

    @Test
    void enabledWithoutOwnedImplementationsFailsAtStartup() {
        enabled().run(c -> assertThat(c).hasFailed());
    }

    @Test
    void enabledWithoutGoalEvaluatorCannotSilentlySkipActivityCompletion() {
        portsWithoutGoals(enabled()).run(c -> assertThat(c).hasFailed());
    }

    @Test
    void absentUploadPolicyCannotBecomeAnImplicitProductionDefault() {
        ports(runner().withPropertyValues("roleplay.http.enabled=true"))
                .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void policyBeyondExistingSpeechCapacityFailsAtStartup() {
        ports(
                        enabled()
                                .withPropertyValues(
                                        "roleplay.http.maximum-upload-bytes=2097153",
                                        "roleplay.http.maximum-request-bytes=3000000"))
                .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void turnBudgetCannotExceedSixtySeconds() {
        ports(enabled().withPropertyValues("roleplay.http.turn-budget=61s"))
                .run(c -> assertThat(c).hasFailed());
    }
}
