package com.neuringo.neuringobe.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.infrastructure.springai.SpringAiFailureMapper;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;

class SpringAiFailureMapperTest {

    private final SpringAiFailureMapper mapper = new SpringAiFailureMapper();

    @Test
    void mapsTimeoutWithoutExposingExceptionMessage() {
        AiFailure failure = mapper.map(new HttpTimeoutException("sensitive response"));

        assertThat(failure.type()).isEqualTo(AiFailureType.TIMEOUT);
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.providerErrorCode()).isEqualTo("HttpTimeoutException");
        assertThat(failure.providerErrorCode()).doesNotContain("sensitive response");
    }

    @Test
    void mapsConnectionFailure() {
        AiFailure failure = mapper.map(new ConnectException("connection refused"));

        assertThat(failure.type()).isEqualTo(AiFailureType.NETWORK_ERROR);
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void mapsNestedRateLimitFailureByProviderExceptionType() {
        AiFailure failure = mapper.map(new RuntimeException(new RateLimitException()));

        assertThat(failure.type()).isEqualTo(AiFailureType.RATE_LIMITED);
        assertThat(failure.retryable()).isTrue();
    }

    private static final class RateLimitException extends RuntimeException {}
}
