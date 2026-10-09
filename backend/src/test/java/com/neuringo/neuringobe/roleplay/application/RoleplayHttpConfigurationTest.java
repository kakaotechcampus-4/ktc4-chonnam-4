package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.neuringo.neuringobe.ai.application.port.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.config.RoleplayHttpConfiguration;
import com.neuringo.neuringobe.roleplay.controller.RoleplayVoiceController;
import com.neuringo.neuringobe.roleplay.service.*;
import jakarta.servlet.MultipartConfigElement;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class RoleplayHttpConfigurationTest {
    @TempDir Path directory;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        RoleplayHttpConfiguration.class, RoleplayVoiceController.class);
    }

    private ApplicationContextRunner enabled() {
        return runner().withPropertyValues(
                        "roleplay.http.enabled=true",
                        "roleplay.http.spool-directory=" + directory,
                        "roleplay.http.maximum-upload-bytes=16",
                        "roleplay.http.maximum-request-bytes=65536",
                        "roleplay.http.allowed-formats=WEBM",
                        "roleplay.http.worker-count=1",
                        "roleplay.http.queue-capacity=0");
    }

    private ApplicationContextRunner ports(ApplicationContextRunner runner) {
        return runner.withBean(
                        RoleplayRequestScopeService.class,
                        () -> mock(RoleplayRequestScopeService.class))
                .withBean(RoleplayCheckpointStore.class, () -> mock(RoleplayCheckpointStore.class))
                .withBean(RoleplayRequestGuard.class, () -> mock(RoleplayRequestGuard.class))
                .withBean(RoleplayContextSource.class, () -> mock(RoleplayContextSource.class))
                .withBean(RoleplayInputProcessor.class, () -> mock(RoleplayInputProcessor.class))
                .withBean(LlmProvider.class, () -> mock(LlmProvider.class))
                .withBean(SpeechToTextProvider.class, () -> mock(SpeechToTextProvider.class))
                .withBean(TextToSpeechProvider.class, () -> mock(TextToSpeechProvider.class))
                .withBean(RoleplaySpeechDelivery.class, () -> mock(RoleplaySpeechDelivery.class))
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build());
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
    void enabledWithoutOwnedImplementationsFailsAtStartup() {
        enabled().run(c -> assertThat(c).hasFailed());
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
