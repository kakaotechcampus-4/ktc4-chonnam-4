package com.neuringo.neuringobe.ai.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AiProviderConfigurationTest {

    @Test
    void acceptsZeroSdkRetries() {
        var environment = new MockEnvironment().withProperty("spring.ai.openai.max-retries", "0");

        assertThatCode(() -> AiProviderConfiguration.requireNoSdkRetries(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSdkRetries() {
        var environment = new MockEnvironment().withProperty("spring.ai.openai.max-retries", "2");

        assertThatThrownBy(() -> AiProviderConfiguration.requireNoSdkRetries(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingSettingBecauseSpringAiDefaultsToThreeRetries() {
        assertThatThrownBy(() -> AiProviderConfiguration.requireNoSdkRetries(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }
}
