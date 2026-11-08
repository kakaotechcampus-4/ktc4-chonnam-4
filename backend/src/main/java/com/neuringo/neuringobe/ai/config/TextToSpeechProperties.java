package com.neuringo.neuringobe.ai.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TTS 설정. 비워 둔 값은 공급자(provider)별 기본값을 쓴다.
 *
 * <ul>
 *   <li>typecast: api.typecast.ai, ssfm-v30, 언어 kor. 목소리 ID 는 반드시 넣는다.
 *   <li>openai: api.openai.com, gpt-4o-mini-tts, 목소리 nova.
 * </ul>
 */
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
        provider = blankToDefault(provider, "none").toLowerCase(Locale.ROOT);
        boolean openai = provider.equals("openai");
        providerName = blankToDefault(providerName, openai ? "openai" : "typecast");
        baseUrl =
                blankToDefault(
                        baseUrl, openai ? "https://api.openai.com" : "https://api.typecast.ai");
        model = blankToDefault(model, openai ? "gpt-4o-mini-tts" : "ssfm-v30");
        language = openai ? blankToNull(language) : blankToDefault(language, "kor");
        audioFormat = blankToDefault(audioFormat, "mp3");
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(15);
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        voiceId = openai ? blankToDefault(voiceId, "nova") : blankToNull(voiceId);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
