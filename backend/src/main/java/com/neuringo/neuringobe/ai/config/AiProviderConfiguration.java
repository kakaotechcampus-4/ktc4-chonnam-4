package com.neuringo.neuringobe.ai.config;

import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import com.neuringo.neuringobe.ai.infrastructure.springai.SpringAiLlmProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProviderProperties.class)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class AiProviderConfiguration {

    private static final int SPRING_AI_DEFAULT_RETRIES = 3;

    @Bean
    LlmProvider llmProvider(
            ChatModel chatModel, AiProviderProperties properties, Environment environment) {
        var provider =
                new SpringAiLlmProvider(
                        chatModel,
                        new OpenAiFailureClassifier(),
                        properties.providerName(),
                        properties.requestTimeout(),
                        properties.maxRetries(),
                        properties.model());
        if (environment.getProperty("roleplay.http.enabled", Boolean.class, false)) {
            provider.requireSingleAttempt();
            requireNoSdkRetries(environment);
        }
        return provider;
    }

    /** Spring AI 가 실제 OpenAI 클라이언트를 만들 때 쓰는 재시도 횟수. 값이 없으면 라이브러리 기본값 3 이 쓰인다. */
    static void requireNoSdkRetries(Environment environment) {
        int sdkRetries =
                environment.getProperty(
                        "spring.ai.openai.max-retries", Integer.class, SPRING_AI_DEFAULT_RETRIES);
        if (sdkRetries != 0)
            throw new IllegalStateException(
                    "Budgeted LLM calls require spring.ai.openai.max-retries to be zero");
    }
}
