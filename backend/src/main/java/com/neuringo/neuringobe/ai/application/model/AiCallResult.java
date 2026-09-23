package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

public sealed interface AiCallResult<T> permits AiCallResult.Success, AiCallResult.Failure {

    record Success<T>(T data, AiCallMetadata metadata) implements AiCallResult<T> {

        public Success {
            Objects.requireNonNull(data, "data must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
        }
    }

    record Failure<T>(AiFailure failure, AiCallMetadata metadata) implements AiCallResult<T> {

        public Failure {
            Objects.requireNonNull(failure, "failure must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
        }
    }
}
