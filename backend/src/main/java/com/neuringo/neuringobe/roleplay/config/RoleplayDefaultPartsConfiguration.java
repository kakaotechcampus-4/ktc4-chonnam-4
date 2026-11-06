package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.infrastructure.speech.DisabledSpeechToTextProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.DisabledTextToSpeechProvider;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import com.neuringo.neuringobe.roleplay.application.RuleBasedRoleplayInputProcessor;
import com.neuringo.neuringobe.roleplay.application.TextOnlyRoleplaySpeechDelivery;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 역할극 대화 API 를 켰을 때 서버가 쓰는 기본 입력 처리·음성 전달 부품. 다른 구현(테스트 부품, 이후의 운영 구현)이 있으면 그쪽을 쓴다.
 *
 * <p>STT·TTS 를 켜지 않았으면(none) "꺼짐" 부품을 넣는다. 음성 답은 재입력 안내로 끝나고 글자 답은 소리 없이 대화가 이어진다.
 *
 * <p>자동 설정으로 두어 앱·테스트 설정을 모두 읽은 뒤에 "없으면 등록"을 판단한다. 일반 설정 클래스였다면 읽는 순서에 따라 테스트 부품과 함께 등록돼 어느 것을 쓸지
 * 정하지 못한다.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public class RoleplayDefaultPartsConfiguration {

    @Bean
    @ConditionalOnMissingBean(RoleplayInputProcessor.class)
    RoleplayInputProcessor ruleBasedRoleplayInputProcessor() {
        return new RuleBasedRoleplayInputProcessor();
    }

    @Bean
    @ConditionalOnMissingBean(RoleplaySpeechDelivery.class)
    RoleplaySpeechDelivery textOnlyRoleplaySpeechDelivery() {
        return new TextOnlyRoleplaySpeechDelivery();
    }

    @Bean
    @ConditionalOnMissingBean(SpeechToTextProvider.class)
    SpeechToTextProvider disabledSpeechToTextProvider() {
        return new DisabledSpeechToTextProvider();
    }

    @Bean
    @ConditionalOnMissingBean(TextToSpeechProvider.class)
    TextToSpeechProvider disabledTextToSpeechProvider() {
        return new DisabledTextToSpeechProvider();
    }
}
