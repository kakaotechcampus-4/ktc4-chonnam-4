package com.neuringo.neuringobe.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "neuringo.ai.tts")
public record TextToSpeechProperties(
        String provider,
        String providerName,
        String baseUrl,
        String apiKey,
        String model,
        String voiceId,
        String language,
        String audioFormat,
        Duration requestTimeout) {

    public TextToSpeechProperties {
        provider = blankToDefault(provider, "none");
        providerName = blankToDefault(providerName, "typecast");
        baseUrl = blankToDefault(baseUrl, "https://api.typecast.ai");
        model = blankToDefault(model, "ssfm-v30");
        language = blankToDefault(language, "kor");
        audioFormat = blankToDefault(audioFormat, "mp3");
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(15);
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (voiceId != null && voiceId.isBlank()) {
            voiceId = null;
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
