package com.neuringo.neuringobe.child.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChildEntryAttemptProperties.class)
public class ChildEntryAttemptConfiguration {

    @Bean
    public ChildEntryAttemptLimiter childEntryAttemptLimiter(
            ChildEntryAttemptProperties properties, Clock clock) {
        return new ChildEntryAttemptLimiter(properties, clock);
    }
}
