package com.neuringo.neuringobe.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.OpenAiTextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.TypecastTextToSpeechProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SpeechProviderConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            SpeechProviderConfiguration.OpenAiSpeechToText.class,
                            SpeechProviderConfiguration.TypecastTextToSpeech.class,
                            SpeechProviderConfiguration.OpenAiTextToSpeech.class);

    @Test
    void createsNoSpeechProvidersByDefault() {
        runner.run(
                context -> {
                    assertThat(context).doesNotHaveBean(SpeechToTextProvider.class);
                    assertThat(context).doesNotHaveBean(TextToSpeechProvider.class);
                });
    }

    @Test
    void createsProvidersWhenEnabledAndConfigured() {
        runner.withPropertyValues(
                        "neuringo.ai.stt.provider=openai",
                        "neuringo.ai.stt.api-key=stt-key",
                        "neuringo.ai.tts.provider=typecast",
                        "neuringo.ai.tts.api-key=tts-key",
                        "neuringo.ai.tts.voice-id=tc_default")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(SpeechToTextProvider.class);
                            assertThat(context).hasSingleBean(TextToSpeechProvider.class);
                            assertThat(context.getBean(SpeechToTextProperties.class).model())
                                    .isEqualTo("gpt-4o-mini-transcribe");
                            assertThat(context.getBean(TextToSpeechProperties.class).model())
                                    .isEqualTo("ssfm-v30");
                        });
    }

    @Test
    void failsFastWhenSttApiKeyIsMissing() {
        runner.withPropertyValues("neuringo.ai.stt.provider=openai")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsFastWhenTtsVoiceIsMissing() {
        runner.withPropertyValues(
                        "neuringo.ai.tts.provider=typecast", "neuringo.ai.tts.api-key=tts-key")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void createsOpenAiTtsWithProviderDefaults() {
        runner.withPropertyValues(
                        "neuringo.ai.tts.provider=openai", "neuringo.ai.tts.api-key=tts-key")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(TextToSpeechProvider.class))
                                    .isInstanceOf(OpenAiTextToSpeechProvider.class);
                            var properties = context.getBean(TextToSpeechProperties.class);
                            assertThat(properties.baseUrl()).isEqualTo("https://api.openai.com");
                            assertThat(properties.model()).isEqualTo("gpt-4o-mini-tts");
                            assertThat(properties.voiceId()).isEqualTo("nova");
                            assertThat(properties.providerName()).isEqualTo("openai");
                        });
    }

    @Test
    void typecastKeepsItsDefaultsWhenValuesAreBlank() {
        runner.withPropertyValues(
                        "neuringo.ai.tts.provider=typecast",
                        "neuringo.ai.tts.api-key=tts-key",
                        "neuringo.ai.tts.voice-id=tc_default",
                        "neuringo.ai.tts.base-url=",
                        "neuringo.ai.tts.model=",
                        "neuringo.ai.tts.language=")
                .run(
                        context -> {
                            assertThat(context.getBean(TextToSpeechProvider.class))
                                    .isInstanceOf(TypecastTextToSpeechProvider.class);
                            var properties = context.getBean(TextToSpeechProperties.class);
                            assertThat(properties.baseUrl()).isEqualTo("https://api.typecast.ai");
                            assertThat(properties.model()).isEqualTo("ssfm-v30");
                            assertThat(properties.language()).isEqualTo("kor");
                        });
    }

    @Test
    void openAiTtsFallsBackToSttKeyWhenTtsKeyIsBlank() {
        runner.withPropertyValues(
                        "neuringo.ai.stt.provider=openai",
                        "neuringo.ai.stt.api-key=stt-key",
                        "neuringo.ai.tts.provider=openai")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(TextToSpeechProvider.class))
                                    .isInstanceOf(OpenAiTextToSpeechProvider.class);
                        });
    }

    @Test
    void openAiTtsFailsFastWithoutAnyKey() {
        runner.withPropertyValues("neuringo.ai.tts.provider=openai")
                .run(context -> assertThat(context).hasFailed());
    }
}
