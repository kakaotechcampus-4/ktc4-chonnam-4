package com.neuringo.neuringobe.ai.application.model;

public enum AiFailureType {
    TIMEOUT(true),
    NETWORK_ERROR(true),
    RATE_LIMITED(true),
    AUTHENTICATION_ERROR(false),
    PROVIDER_UNAVAILABLE(true),
    PROVIDER_REQUEST_REJECTED(false),
    PROVIDER_RESPONSE_ERROR(true),
    EMPTY_OUTPUT(true),
    INVALID_OUTPUT_FORMAT(true),
    UNKNOWN(false);

    private final boolean retryable;

    AiFailureType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
