package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.roleplay.application.LocalScriptInputProcessor;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 로컬 끝까지 돌려 보기에서만 켠다(scripts/local/run-backend.ps1). 역할극 HTTP API 가 요구하지만 팀 코드에 운영 구현이 아직 없는 두 빈을
 * 채운다. 운영 구현이 생기면 그쪽 빈을 쓰고 이 설정은 켜지 않는다.
 *
 * <ul>
 *   <li>입력 처리: 대본 답의 공백만 정리한다. 개인정보 거르기·안전 판단을 하지 않으므로 실제 아동 음성에는 쓰지 않는다.
 *   <li>음성 전달: 음성을 내보내지 않는다(TTS 도 silent 라 전달할 음성이 없다). 응답은 글로만 간다.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "roleplay.local-script-input", havingValue = "true")
public class LocalScriptInputConfiguration {

    @Bean
    RoleplayInputProcessor roleplayInputProcessor() {
        return new LocalScriptInputProcessor();
    }

    @Bean
    RoleplaySpeechDelivery roleplaySpeechDelivery() {
        return (candidateId, speech) -> Optional.empty();
    }
}
