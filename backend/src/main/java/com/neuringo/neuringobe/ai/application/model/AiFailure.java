package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

public record AiFailure(AiFailureType type, boolean retryable, String providerErrorCode) {

    public AiFailure {
        Objects.requireNonNull(type, "type must not be null");
    }
}
