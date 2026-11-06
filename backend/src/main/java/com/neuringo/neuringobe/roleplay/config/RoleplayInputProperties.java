package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplaySpeechTurnPipeline;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("roleplay.input")
public record RoleplayInputProperties(Double sttConfidenceThreshold) {
    public RoleplayInputProperties {
        sttConfidenceThreshold =
                sttConfidenceThreshold == null
                        ? RoleplaySpeechTurnPipeline.DEFAULT_STT_CONFIDENCE_THRESHOLD
                        : sttConfidenceThreshold;
        if (!Double.isFinite(sttConfidenceThreshold)
                || sttConfidenceThreshold < 0
                || sttConfidenceThreshold > 1)
            throw new IllegalArgumentException("STT 신뢰도 기준은 0부터 1 사이의 유한한 값이어야 합니다.");
    }
}
