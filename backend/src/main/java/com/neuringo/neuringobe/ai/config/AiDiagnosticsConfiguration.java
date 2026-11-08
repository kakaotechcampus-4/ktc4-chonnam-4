package com.neuringo.neuringobe.ai.config;

import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.ai.infrastructure.diagnostics.FileAiDiagnostics;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class AiDiagnosticsConfiguration {
    @Bean
    public AiDiagnostics aiDiagnostics(Environment env) {
        if (!env.getProperty("neuringo.ai.diagnostics.enabled", Boolean.class, false))
            return AiDiagnostics.NONE;
        if (!env.acceptsProfiles(Profiles.of("dev"))
                || env.acceptsProfiles(Profiles.of("prod"))
                || !env.getProperty("neuringo.ai.call-log", Boolean.class, false))
            throw new IllegalStateException("원문 진단은 호출 관측을 켠 dev 전용 실험에서만 허용합니다.");
        return new FileAiDiagnostics(
                Path.of("/tmp/neuringo-diagnostics"),
                Duration.parse(env.getProperty("neuringo.ai.diagnostics.window", "PT15M")),
                env.getProperty("neuringo.ai.diagnostics.max-requests", Integer.class, 10),
                env.getProperty("neuringo.ai.diagnostics.max-bytes", Long.class, 20L * 1024 * 1024),
                env.getProperty(
                        "neuringo.ai.diagnostics.max-event-bytes", Integer.class, 1024 * 1024));
    }
}
