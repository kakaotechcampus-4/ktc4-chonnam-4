package com.neuringo.neuringobe.ai.config;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.OpenAiSpeechToTextProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.TypecastTextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** STT·TTS 는 LLM 과 따로 켠다. 기본값은 둘 다 꺼짐(none)이다. */
public final class SpeechProviderConfiguration {

    private SpeechProviderConfiguration() {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpeechToTextProperties.class)
    @ConditionalOnProperty(name = "neuringo.ai.stt.provider", havingValue = "openai")
    static class OpenAiSpeechToText {

        @Bean
        SpeechToTextProvider speechToTextProvider(SpeechToTextProperties properties) {
            return new OpenAiSpeechToTextProvider(
                    properties.baseUrl(),
                    requireConfigured(properties.apiKey(), "neuringo.ai.stt.api-key"),
                    properties.requestTimeout(),
                    new OpenAiFailureClassifier(),
                    properties.providerName(),
                    properties.model(),
                    properties.language(),
                    properties.includeLogprobs());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TextToSpeechProperties.class)
    @ConditionalOnProperty(name = "neuringo.ai.tts.provider", havingValue = "typecast")
    static class TypecastTextToSpeech {

        @Bean
        TextToSpeechProvider textToSpeechProvider(TextToSpeechProperties properties) {
            return new TypecastTextToSpeechProvider(
                    properties.baseUrl(),
                    requireConfigured(properties.apiKey(), "neuringo.ai.tts.api-key"),
                    properties.requestTimeout(),
                    new OpenAiFailureClassifier(),
                    properties.providerName(),
                    properties.model(),
                    requireConfigured(properties.voiceId(), "neuringo.ai.tts.voice-id"),
                    properties.language(),
                    AudioFormat.valueOf(properties.audioFormat().toUpperCase(Locale.ROOT)));
        }
    }

    private static String requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank() || value.equals("not-configured")) {
            throw new IllegalStateException(propertyName + " must be configured");
        }
        return value;
    }
}
