package com.neuringo.neuringobe.ai.config;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.OpenAiSpeechToTextProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.OpenAiTextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.ScriptedSpeechToTextProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.SilentTextToSpeechProvider;
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

    /** 로컬 끝까지 돌려 보기 전용. 올린 WAV 의 대본 조각을 전사 결과로 돌려준다(운영에 쓰지 않는다). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "neuringo.ai.stt.provider", havingValue = "scripted")
    static class ScriptedSpeechToText {

        @Bean
        SpeechToTextProvider speechToTextProvider() {
            return new ScriptedSpeechToTextProvider();
        }
    }

    /** 로컬 끝까지 돌려 보기 전용. 음성 없이 글로만 응답한다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "neuringo.ai.tts.provider", havingValue = "silent")
    static class SilentTextToSpeech {

        @Bean
        TextToSpeechProvider textToSpeechProvider() {
            return new SilentTextToSpeechProvider();
        }
    }

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

    /** OpenAI TTS. STT 와 같은 OpenAI 키를 쓸 수 있다. 목소리를 비우면 nova 다. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TextToSpeechProperties.class)
    @ConditionalOnProperty(name = "neuringo.ai.tts.provider", havingValue = "openai")
    static class OpenAiTextToSpeech {

        @Bean
        TextToSpeechProvider textToSpeechProvider(TextToSpeechProperties properties) {
            return new OpenAiTextToSpeechProvider(
                    properties.baseUrl(),
                    requireConfigured(properties.apiKey(), "neuringo.ai.tts.api-key"),
                    properties.requestTimeout(),
                    new OpenAiFailureClassifier(),
                    properties.providerName(),
                    properties.model(),
                    properties.voiceId(),
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
