package com.neuringo.neuringobe.ai.application.model;

import java.util.UUID;

public record AiCallMetadata(
        UUID requestId,
        AiOperation operation,
        String provider,
        String model,
        String promptVersion,
        String responseSchemaVersion,
        String policyVersion,
        long elapsedMs,
        int attemptNo,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason) {}
