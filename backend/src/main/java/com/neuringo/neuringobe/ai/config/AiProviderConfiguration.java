package com.neuringo.neuringobe.ai.config;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.infrastructure.diagnostics.DiagnosticLlmProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.CallLoggingLlmProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import com.neuringo.neuringobe.ai.infrastructure.springai.OperationRoutingLlmProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.SpringAiLlmProvider;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiProviderProperties.class, AiRouteProperties.class})
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class AiProviderConfiguration {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AiProviderConfiguration.class);

    private static final int SPRING_AI_DEFAULT_RETRIES = 3;

    @Bean
    LlmProvider llmProvider(
            ChatModel chatModel,
            AiProviderProperties properties,
            AiRouteProperties routes,
            Environment environment,
            Optional<AiDiagnostics> diagnostics) {
        var provider =
                new SpringAiLlmProvider(
                        chatModel,
                        new OpenAiFailureClassifier(),
                        properties.providerName(),
                        properties.requestTimeout(),
                        properties.maxRetries(),
                        properties.model(),
                        properties.maxOutputTokens(),
                        properties.reasoningEffort());
        if (environment.getProperty("roleplay.http.enabled", Boolean.class, false)) {
            provider.requireSingleAttempt();
            requireNoSdkRetries(environment);
        }
        LlmProvider chosen = provider;
        var active = routes.active();
        if (!active.isEmpty()) {
            String apiKey = environment.getProperty("spring.ai.openai.api-key");
            Map<AiOperation, LlmProvider> routed = new EnumMap<>(AiOperation.class);
            active.forEach(
                    (operation, route) ->
                            routed.put(operation, routeProvider(route, apiKey, properties)));
            chosen = new OperationRoutingLlmProvider(provider, routed);
        }
        chosen = new DiagnosticLlmProvider(chosen, diagnostics.orElse(AiDiagnostics.NONE));
        if (environment.getProperty("neuringo.ai.call-log", Boolean.class, false)) {
            log.info(
                    "AI_SETTINGS scope=DEFAULT model={} reasoning={} timeout_ms={} max_output_tokens={} sdk_retries={}",
                    properties.model(),
                    properties.reasoningEffort(),
                    properties.requestTimeout().toMillis(),
                    properties.maxOutputTokens(),
                    properties.maxRetries());
            active.forEach(
                    (operation, route) ->
                            log.info(
                                    "AI_SETTINGS scope={} model={} reasoning={} timeout_ms={} max_output_tokens={} sdk_retries=0",
                                    operation,
                                    route.model(),
                                    route.reasoningEffort(),
                                    route.requestTimeout().toMillis(),
                                    route.maxOutputTokens()));
            return new CallLoggingLlmProvider(chosen);
        }
        return chosen;
    }

    /**
     * 경로마다 주소가 다른 OpenAI 호환 클라이언트를 따로 만든다. 재시도는 호출하는 쪽(리포트 생성 루프)이 맡으므로 SDK 와 제공자 재시도는 모두 0 으로 둔다.
     */
    static LlmProvider routeProvider(
            AiRouteProperties.Route route, String apiKey, AiProviderProperties properties) {
        var chatModel =
                OpenAiChatModel.builder()
                        .options(
                                OpenAiChatOptions.builder()
                                        .baseUrl(route.baseUrl())
                                        .apiKey(apiKey)
                                        .model(route.model())
                                        .timeout(route.requestTimeout())
                                        .maxRetries(0)
                                        .build())
                        .build();
        return new SpringAiLlmProvider(
                chatModel,
                new OpenAiFailureClassifier(),
                properties.providerName(),
                route.requestTimeout(),
                0,
                route.model(),
                route.maxOutputTokens(),
                route.reasoningEffort());
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
