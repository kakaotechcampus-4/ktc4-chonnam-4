package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import com.neuringo.neuringobe.roleplay.infrastructure.speech.InlineRoleplaySpeechDelivery;
import com.neuringo.neuringobe.roleplay.infrastructure.speech.NoAudioRoleplaySpeechDelivery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 음성 전달 방식은 명시적으로 고른다. none: 글만 보낸다. inline: 합성한 음성을 응답 안의 data URL 로 함께 보낸다. 미설정이면 빈을 만들지 않아 시작이
 * 실패한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public class RoleplaySpeechDeliveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = "roleplay.speech.delivery", havingValue = "none")
    @ConditionalOnMissingBean(RoleplaySpeechDelivery.class)
    public RoleplaySpeechDelivery roleplaySpeechDelivery() {
        return new NoAudioRoleplaySpeechDelivery();
    }

    @Bean
    @ConditionalOnProperty(name = "roleplay.speech.delivery", havingValue = "inline")
    @ConditionalOnMissingBean(RoleplaySpeechDelivery.class)
    public RoleplaySpeechDelivery inlineRoleplaySpeechDelivery() {
        return new InlineRoleplaySpeechDelivery();
    }
}
