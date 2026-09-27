package com.neuringo.neuringobe.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "neuringo.ai")
public record AiProviderProperties(String providerName, Duration requestTimeout, int maxRetries) {

    public AiProviderProperties {
        if (providerName == null || providerName.isBlank()) {
            providerName = "openai-compatible";
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(10);
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
    }
}
