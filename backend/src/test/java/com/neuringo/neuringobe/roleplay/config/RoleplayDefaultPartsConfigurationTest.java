package com.neuringo.neuringobe.roleplay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.infrastructure.speech.DisabledSpeechToTextProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.DisabledTextToSpeechProvider;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import com.neuringo.neuringobe.roleplay.application.RuleBasedRoleplayInputProcessor;
import com.neuringo.neuringobe.roleplay.application.TextOnlyRoleplaySpeechDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RoleplayDefaultPartsConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(RoleplayDefaultPartsConfiguration.class));

    @Test
    void usesRuleBasedInputAndTextOnlyDeliveryWhenApiEnabled() {
        runner.withPropertyValues("roleplay.http.enabled=true")
                .run(
                        c -> {
                            assertThat(c).hasSingleBean(RoleplayInputProcessor.class);
                            assertThat(c.getBean(RoleplayInputProcessor.class))
                                    .isInstanceOf(RuleBasedRoleplayInputProcessor.class);
                            assertThat(c.getBean(RoleplaySpeechDelivery.class))
                                    .isInstanceOf(TextOnlyRoleplaySpeechDelivery.class);
                            assertThat(c.getBean(SpeechToTextProvider.class))
                                    .isInstanceOf(DisabledSpeechToTextProvider.class);
                            assertThat(c.getBean(TextToSpeechProvider.class))
                                    .isInstanceOf(DisabledTextToSpeechProvider.class);
                        });
    }

    @Test
    void keepsConfiguredSpeechProviders() {
        SpeechToTextProvider stt = mock(SpeechToTextProvider.class);
        TextToSpeechProvider tts = mock(TextToSpeechProvider.class);
        runner.withPropertyValues("roleplay.http.enabled=true")
                .withBean(SpeechToTextProvider.class, () -> stt)
                .withBean(TextToSpeechProvider.class, () -> tts)
                .run(
                        c -> {
                            assertThat(c.getBean(SpeechToTextProvider.class)).isSameAs(stt);
                            assertThat(c.getBean(TextToSpeechProvider.class)).isSameAs(tts);
                        });
    }

    @Test
    void backsOffWhenAnotherImplementationExists() {
        RoleplayInputProcessor custom = mock(RoleplayInputProcessor.class);
        RoleplaySpeechDelivery delivery = mock(RoleplaySpeechDelivery.class);
        runner.withPropertyValues("roleplay.http.enabled=true")
                .withBean(RoleplayInputProcessor.class, () -> custom)
                .withBean(RoleplaySpeechDelivery.class, () -> delivery)
                .run(
                        c -> {
                            assertThat(c.getBean(RoleplayInputProcessor.class)).isSameAs(custom);
                            assertThat(c.getBean(RoleplaySpeechDelivery.class)).isSameAs(delivery);
                        });
    }

    @Test
    void registersNothingWhenApiDisabled() {
        runner.run(
                c -> {
                    assertThat(c).doesNotHaveBean(RoleplayInputProcessor.class);
                    assertThat(c).doesNotHaveBean(RoleplaySpeechDelivery.class);
                    assertThat(c).doesNotHaveBean(SpeechToTextProvider.class);
                    assertThat(c).doesNotHaveBean(TextToSpeechProvider.class);
                });
    }
}
