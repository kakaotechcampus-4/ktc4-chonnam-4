package com.neuringo.neuringobe.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 세션 만료 판단이 현재 시각에 의존하므로, 테스트에서 시각을 바꿀 수 있게 {@link Clock} 을 빈으로 둔다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
