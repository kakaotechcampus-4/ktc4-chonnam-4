package com.neuringo.neuringobe.scenario.config;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FollowUpDifficultyProperties.class)
public class FollowUpDifficultyConfiguration {

    @Bean
    FollowUpDifficultyPolicy followUpDifficultyPolicy(FollowUpDifficultyProperties properties) {
        return new FollowUpDifficultyPolicy(properties.rules());
    }
}
