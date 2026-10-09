package com.neuringo.neuringobe.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "neuringo.ai.stt")
public record SpeechToTextProperties(
        String provider,
        String providerName,
        String baseUrl,
        String apiKey,
        String model,
        String language,
        Duration requestTimeout,
        Boolean includeLogprobs) {

    public SpeechToTextProperties {
        provider = blankToDefault(provider, "none");
        providerName = blankToDefault(providerName, "openai");
        baseUrl = blankToDefault(baseUrl, "https://api.openai.com");
        model = blankToDefault(model, "gpt-4o-mini-transcribe");
        language = blankToDefault(language, "ko");
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(15);
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (includeLogprobs == null) {
            includeLogprobs = true;
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
