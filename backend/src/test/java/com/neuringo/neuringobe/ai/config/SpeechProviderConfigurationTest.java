package com.neuringo.neuringobe.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SpeechProviderConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            SpeechProviderConfiguration.OpenAiSpeechToText.class,
                            SpeechProviderConfiguration.TypecastTextToSpeech.class);

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
}
