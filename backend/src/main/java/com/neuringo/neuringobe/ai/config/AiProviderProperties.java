package com.neuringo.neuringobe.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "neuringo.ai")
/**
 * @param maxOutputTokens 호출 한 번의 출력 토큰 상한(max_completion_tokens). 없으면 제공자 기본값
 * @param reasoningEffort 추론 모델의 추론 강도(none·low·medium·high 등). 없으면 보내지 않는다. 추론을 지원하지 않는 모델에는 넣지 않는다
 */
public record AiProviderProperties(
        String providerName,
        Duration requestTimeout,
        int maxRetries,
        String model,
        Integer maxOutputTokens,
        String reasoningEffort) {

    public AiProviderProperties {
        if (providerName == null || providerName.isBlank()) {
            providerName = "openai-compatible";
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(10);
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (reasoningEffort != null && reasoningEffort.isBlank()) {
            reasoningEffort = null;
        }
    }
}
