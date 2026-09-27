package com.neuringo.neuringobe.ai.application.model;

public enum AiFailureType {
    TIMEOUT,
    NETWORK_ERROR,
    RATE_LIMITED,
    AUTHENTICATION_ERROR,
    PROVIDER_UNAVAILABLE,
    PROVIDER_RESPONSE_ERROR,
    EMPTY_OUTPUT,
    INVALID_OUTPUT_FORMAT,
    UNKNOWN
}
