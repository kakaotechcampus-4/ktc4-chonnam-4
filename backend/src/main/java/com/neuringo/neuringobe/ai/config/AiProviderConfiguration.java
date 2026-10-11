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
        if (environment.getProperty("roleplay.http.enabled", Boolean.class, false))
            provider.requireSingleAttempt();
        return provider;
    }
}
