package com.neuringo.neuringobe.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.infrastructure.springai.FailureMapping;
import com.neuringo.neuringobe.ai.infrastructure.springai.FailureMappingSource;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import com.openai.errors.BadRequestException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnprocessableEntityException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class OpenAiFailureClassifierTest {

    private final OpenAiFailureClassifier classifier = new OpenAiFailureClassifier();

    @Test
    void mapsTimeoutByJdkTypeWithoutExposingExceptionMessage() {
        FailureMapping mapping = required(classifier.classify(new HttpTimeoutException("secret")));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.TIMEOUT);
        assertThat(mapping.failure().retryable()).isTrue();
        assertThat(mapping.failure().providerErrorCode()).isEqualTo("HttpTimeoutException");
        assertThat(mapping.failure().providerErrorCode()).doesNotContain("secret");
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.JDK_EXCEPTION_TYPE);
    }

    @Test
    void givesNestedTimeoutPriorityOverOuterIoFailure() {
        RuntimeException outer = new RuntimeException(new SocketTimeoutException("timed out"));

        FailureMapping mapping = required(classifier.classify(outer));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.TIMEOUT);
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.JDK_EXCEPTION_TYPE);
    }

    @Test
    void mapsInterruptedIoFromHttpClientToTimeout() {
        RuntimeException outer =
                new RuntimeException(new InterruptedIOException("request interrupted"));

        FailureMapping mapping = required(classifier.classify(outer));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.TIMEOUT);
        assertThat(mapping.failure().retryable()).isTrue();
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.JDK_EXCEPTION_TYPE);
    }

    @Test
    void mapsConnectionFailureByJdkType() {
        FailureMapping mapping = required(classifier.classify(new ConnectException("refused")));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.NETWORK_ERROR);
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.JDK_EXCEPTION_TYPE);
    }

    @Test
    void mapsConcreteOpenAiExceptionBeforeStatusCode() {
        RateLimitException exception = mock(RateLimitException.class);

        FailureMapping mapping = required(classifier.classify(exception));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.RATE_LIMITED);
        assertThat(mapping.failure().retryable()).isTrue();
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.OPENAI_EXCEPTION_TYPE);
    }

    @Test
    void mapsUnknownOpenAiServiceExceptionByStatusCode() {
        OpenAIServiceException exception = mock(OpenAIServiceException.class);
        when(exception.statusCode()).thenReturn(503);
        when(exception.code()).thenReturn(Optional.empty());

        FailureMapping mapping = required(classifier.classify(exception));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.PROVIDER_UNAVAILABLE);
        assertThat(mapping.failure().retryable()).isTrue();
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.OPENAI_STATUS_CODE);
    }

    @ParameterizedTest
    @ValueSource(
            classes = {
                BadRequestException.class,
                NotFoundException.class,
                UnprocessableEntityException.class
            })
    void mapsRejectedRequestExceptionToNonRetryableRequestRejected(
            Class<? extends OpenAIServiceException> exceptionType) {
        OpenAIServiceException exception = mock(exceptionType);
        when(exception.code()).thenReturn(Optional.empty());

        FailureMapping mapping = required(classifier.classify(exception));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.PROVIDER_REQUEST_REJECTED);
        assertThat(mapping.failure().retryable()).isFalse();
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.OPENAI_EXCEPTION_TYPE);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 413, 422})
    void mapsClientErrorStatusCodeToNonRetryableRequestRejected(int statusCode) {
        OpenAIServiceException exception = mock(OpenAIServiceException.class);
        when(exception.statusCode()).thenReturn(statusCode);
        when(exception.code()).thenReturn(Optional.empty());

        FailureMapping mapping = required(classifier.classify(exception));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.PROVIDER_REQUEST_REJECTED);
        assertThat(mapping.failure().retryable()).isFalse();
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.OPENAI_STATUS_CODE);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void mapsSpringClientErrorResponseToNonRetryableRequestRejected(int statusCode) {
        HttpClientErrorException exception =
                HttpClientErrorException.create(
                        HttpStatus.valueOf(statusCode), "rejected", null, null, null);

        FailureMapping mapping = required(classifier.classify(exception));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.PROVIDER_REQUEST_REJECTED);
        assertThat(mapping.failure().retryable()).isFalse();
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.SPRING_EXCEPTION_TYPE);
    }

    @Test
    void keepsUnreadableProviderResponseAsRetryableResponseError() {
        FailureMapping mapping =
                required(classifier.classify(new OpenAIInvalidDataException("broken body")));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.PROVIDER_RESPONSE_ERROR);
        assertThat(mapping.failure().retryable()).isTrue();
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.OPENAI_EXCEPTION_TYPE);
    }

    @Test
    void usesExceptionNameOnlyAsLastFallback() {
        FailureMapping mapping = required(classifier.classify(new CustomRateLimitException()));

        assertThat(mapping.failure().type()).isEqualTo(AiFailureType.RATE_LIMITED);
        assertThat(mapping.source()).isEqualTo(FailureMappingSource.NAME_FALLBACK);
        assertThat(mapping.usedFallback()).isTrue();
    }

    @Test
    void doesNotClassifyUnrelatedRuntimeException() {
        assertThat(classifier.classify(new IllegalStateException("internal bug"))).isEmpty();
    }

    private FailureMapping required(Optional<FailureMapping> mapping) {
        return mapping.orElseThrow();
    }

    private static final class CustomRateLimitException extends RuntimeException {}
}
