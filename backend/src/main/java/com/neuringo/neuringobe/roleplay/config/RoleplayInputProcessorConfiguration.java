package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.infrastructure.input.ModelRoleplayInputProcessor;
import com.neuringo.neuringobe.roleplay.service.RoleplayLearnerPrivacyService;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public class RoleplayInputProcessorConfiguration {
    @Bean
    @ConditionalOnMissingBean(RoleplayInputProcessor.class)
    public RoleplayInputProcessor roleplayInputProcessor(
            LlmProvider llm,
            JsonMapper mapper,
            RoleplayLearnerPrivacyService privacy,
            @Value("${neuringo.ai.call-log:false}") boolean callLog,
            Optional<AiDiagnostics> diagnostics) {
        String prompt;
        try (var stream =
                getClass().getResourceAsStream("/prompts/roleplay/v1/input-validation.md")) {
            if (stream == null) throw new IllegalStateException("입력 판정 프롬프트가 없습니다.");
            prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("입력 판정 프롬프트를 읽을 수 없습니다.");
        }
        return new ModelRoleplayInputProcessor(
                llm,
                mapper,
                privacy::learnerName,
                prompt,
                callLog,
                diagnostics.orElse(AiDiagnostics.NONE));
    }
}
