package com.neuringo.neuringobe.ai.application.structured;

public final class InvalidLlmOutputException extends RuntimeException {

    public InvalidLlmOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
