package com.neuringo.neuringobe.ai.application.model;

public record LlmCompletion(
        String content,
        String provider,
        String model,
        String finishReason,
        Integer inputTokens,
        Integer outputTokens) {}
