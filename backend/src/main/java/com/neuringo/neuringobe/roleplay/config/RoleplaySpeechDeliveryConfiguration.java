package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import com.neuringo.neuringobe.roleplay.infrastructure.speech.NoAudioRoleplaySpeechDelivery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public class RoleplaySpeechDeliveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = "roleplay.speech.delivery", havingValue = "none")
    @ConditionalOnMissingBean(RoleplaySpeechDelivery.class)
    public RoleplaySpeechDelivery roleplaySpeechDelivery() {
        return new NoAudioRoleplaySpeechDelivery();
    }
}
