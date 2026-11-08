package com.neuringo.neuringobe.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AiDiagnosticsConfigurationTest {
    @Test
    void defaultsToNoCapture() {
        assertThat(new AiDiagnosticsConfiguration().aiDiagnostics(new MockEnvironment()))
                .isSameAs(AiDiagnostics.NONE);
    }

    @Test
    void refusesProductionAndMissingTechnicalObservation() {
        var env = new MockEnvironment().withProperty("neuringo.ai.diagnostics.enabled", "true");
        var config = new AiDiagnosticsConfiguration();
        assertThatThrownBy(() -> config.aiDiagnostics(env))
                .isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("dev");
        assertThatThrownBy(() -> config.aiDiagnostics(env))
                .isInstanceOf(IllegalStateException.class);
        env.setProperty("neuringo.ai.call-log", "true");
        env.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> config.aiDiagnostics(env))
                .isInstanceOf(IllegalStateException.class);
    }
}
