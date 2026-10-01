package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import java.util.UUID;
import org.assertj.core.api.Assertions;

final class SpeechTestFixtures {

    private SpeechTestFixtures() {}

    static AiTraceContext turnTrace() {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null);
    }

    static <T> AiCallResult.Failure<T> assertFailure(AiCallResult<T> result, AiFailureType type) {
        Assertions.assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        AiCallResult.Failure<T> failure = (AiCallResult.Failure<T>) result;
        Assertions.assertThat(failure.failure().type()).isEqualTo(type);
        return failure;
    }

    static <T> AiCallResult.Success<T> assertSuccess(AiCallResult<T> result) {
        Assertions.assertThat(result).isInstanceOf(AiCallResult.Success.class);
        return (AiCallResult.Success<T>) result;
    }
}
