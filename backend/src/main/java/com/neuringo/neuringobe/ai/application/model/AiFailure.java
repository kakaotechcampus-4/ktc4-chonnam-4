package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

public record AiFailure(AiFailureType type, String providerErrorCode) {

    public AiFailure {
        Objects.requireNonNull(type, "type must not be null");
    }

    public boolean retryable() {
        return type.retryable();
    }
}
