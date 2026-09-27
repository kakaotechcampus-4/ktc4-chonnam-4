package com.neuringo.neuringobe.ai.infrastructure.springai;

import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnprocessableEntityException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public final class OpenAiFailureClassifier {

    public Optional<FailureMapping> classify(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable must not be null");
        List<Throwable> causes = causalChain(throwable);

        Optional<FailureMapping> timeout = firstMatch(causes, this::mapTimeoutType);
        if (timeout.isPresent()) {
            return timeout;
        }

        for (Throwable cause : causes) {
            FailureMapping mapping = mapOpenAiExceptionType(cause);
            if (mapping == null) {
                mapping = mapOpenAiStatusCode(cause);
            }
            if (mapping == null) {
                mapping = mapSpringExceptionType(cause);
            }
            if (mapping == null) {
                mapping = mapJdkExceptionType(cause);
            }
            if (mapping == null) {
                mapping = mapOpenAiBaseType(cause);
            }
            if (mapping != null) {
                return Optional.of(mapping);
            }
        }

        return firstMatch(causes, this::mapNameFallback);
    }

    private FailureMapping mapTimeoutType(Throwable throwable) {
        if (throwable instanceof HttpTimeoutException
                || throwable instanceof HttpConnectTimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof InterruptedIOException
                || throwable instanceof TimeoutException) {
            return mapping(
                    AiFailureType.TIMEOUT,
                    true,
                    throwable,
                    FailureMappingSource.JDK_EXCEPTION_TYPE);
        }
        return null;
    }

    private FailureMapping mapOpenAiExceptionType(Throwable throwable) {
        if (throwable instanceof RateLimitException) {
            return mapping(
                    AiFailureType.RATE_LIMITED,
                    true,
                    throwable,
                    FailureMappingSource.OPENAI_EXCEPTION_TYPE);
        }
        if (throwable instanceof UnauthorizedException
                || throwable instanceof PermissionDeniedException) {
            return mapping(
                    AiFailureType.AUTHENTICATION_ERROR,
                    false,
                    throwable,
                    FailureMappingSource.OPENAI_EXCEPTION_TYPE);
        }
        if (throwable instanceof InternalServerException
                || throwable instanceof OpenAIRetryableException) {
            return mapping(
                    AiFailureType.PROVIDER_UNAVAILABLE,
                    true,
                    throwable,
                    FailureMappingSource.OPENAI_EXCEPTION_TYPE);
        }
        if (throwable instanceof BadRequestException
                || throwable instanceof NotFoundException
                || throwable instanceof UnprocessableEntityException) {
            return mapping(
                    AiFailureType.PROVIDER_RESPONSE_ERROR,
                    false,
                    throwable,
                    FailureMappingSource.OPENAI_EXCEPTION_TYPE);
        }
        if (throwable instanceof OpenAIInvalidDataException) {
            return mapping(
                    AiFailureType.PROVIDER_RESPONSE_ERROR,
                    true,
                    throwable,
                    FailureMappingSource.OPENAI_EXCEPTION_TYPE);
        }
        if (throwable instanceof OpenAIIoException) {
            return mapping(
                    AiFailureType.NETWORK_ERROR,
                    true,
                    throwable,
                    FailureMappingSource.OPENAI_EXCEPTION_TYPE);
        }
        return null;
    }

    private FailureMapping mapOpenAiStatusCode(Throwable throwable) {
        if (!(throwable instanceof OpenAIServiceException exception)) {
            return null;
        }

        int statusCode = exception.statusCode();
        if (statusCode == 401 || statusCode == 403) {
            return mapping(
                    AiFailureType.AUTHENTICATION_ERROR,
                    false,
                    exception,
                    FailureMappingSource.OPENAI_STATUS_CODE);
        }
        if (statusCode == 429) {
            return mapping(
                    AiFailureType.RATE_LIMITED,
                    true,
                    exception,
                    FailureMappingSource.OPENAI_STATUS_CODE);
        }
        if (statusCode >= 500) {
            return mapping(
                    AiFailureType.PROVIDER_UNAVAILABLE,
                    true,
                    exception,
                    FailureMappingSource.OPENAI_STATUS_CODE);
        }
        return mapping(
                AiFailureType.PROVIDER_RESPONSE_ERROR,
                false,
                exception,
                FailureMappingSource.OPENAI_STATUS_CODE);
    }

    private FailureMapping mapSpringExceptionType(Throwable throwable) {
        if (throwable instanceof ResourceAccessException) {
            return mapping(
                    AiFailureType.NETWORK_ERROR,
                    true,
                    throwable,
                    FailureMappingSource.SPRING_EXCEPTION_TYPE);
        }
        if (throwable instanceof RestClientResponseException exception) {
            return mapStatusCode(
                    exception.getStatusCode().value(),
                    exception,
                    FailureMappingSource.SPRING_EXCEPTION_TYPE);
        }
        return null;
    }

    private FailureMapping mapJdkExceptionType(Throwable throwable) {
        if (throwable instanceof ConnectException) {
            return mapping(
                    AiFailureType.NETWORK_ERROR,
                    true,
                    throwable,
                    FailureMappingSource.JDK_EXCEPTION_TYPE);
        }
        return null;
    }

    private FailureMapping mapOpenAiBaseType(Throwable throwable) {
        if (throwable instanceof OpenAIException) {
            return mapping(
                    AiFailureType.UNKNOWN,
                    false,
                    throwable,
                    FailureMappingSource.OPENAI_EXCEPTION_TYPE);
        }
        return null;
    }

    private FailureMapping mapNameFallback(Throwable throwable) {
        String typeName = throwable.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (typeName.contains("timeout")) {
            return mapping(
                    AiFailureType.TIMEOUT, true, throwable, FailureMappingSource.NAME_FALLBACK);
        }
        if (typeName.contains("ratelimit") || typeName.contains("toomanyrequests")) {
            return mapping(
                    AiFailureType.RATE_LIMITED,
                    true,
                    throwable,
                    FailureMappingSource.NAME_FALLBACK);
        }
        if (typeName.contains("authentication")
                || typeName.contains("unauthorized")
                || typeName.contains("permissiondenied")
                || typeName.contains("forbidden")) {
            return mapping(
                    AiFailureType.AUTHENTICATION_ERROR,
                    false,
                    throwable,
                    FailureMappingSource.NAME_FALLBACK);
        }
        if (typeName.contains("internalserver")
                || typeName.contains("serviceunavailable")
                || typeName.contains("badgateway")) {
            return mapping(
                    AiFailureType.PROVIDER_UNAVAILABLE,
                    true,
                    throwable,
                    FailureMappingSource.NAME_FALLBACK);
        }
        if (typeName.contains("json")
                || typeName.contains("decode")
                || typeName.contains("parse")) {
            return mapping(
                    AiFailureType.PROVIDER_RESPONSE_ERROR,
                    true,
                    throwable,
                    FailureMappingSource.NAME_FALLBACK);
        }
        if (typeName.contains("connect") || typeName.contains("network")) {
            return mapping(
                    AiFailureType.NETWORK_ERROR,
                    true,
                    throwable,
                    FailureMappingSource.NAME_FALLBACK);
        }
        return null;
    }

    private FailureMapping mapStatusCode(
            int statusCode, Throwable throwable, FailureMappingSource source) {
        if (statusCode == 401 || statusCode == 403) {
            return mapping(AiFailureType.AUTHENTICATION_ERROR, false, throwable, source);
        }
        if (statusCode == 429) {
            return mapping(AiFailureType.RATE_LIMITED, true, throwable, source);
        }
        if (statusCode >= 500) {
            return mapping(AiFailureType.PROVIDER_UNAVAILABLE, true, throwable, source);
        }
        return mapping(AiFailureType.PROVIDER_RESPONSE_ERROR, false, throwable, source);
    }

    private Optional<FailureMapping> firstMatch(List<Throwable> causes, ExceptionMappingRule rule) {
        for (Throwable cause : causes) {
            FailureMapping mapping = rule.map(cause);
            if (mapping != null) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    private List<Throwable> causalChain(Throwable throwable) {
        List<Throwable> causes = new ArrayList<>();
        Throwable current = throwable;
        while (current != null && !causes.contains(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return causes;
    }

    private FailureMapping mapping(
            AiFailureType type,
            boolean retryable,
            Throwable throwable,
            FailureMappingSource source) {
        String errorCode =
                throwable instanceof OpenAIServiceException exception
                        ? exception.code().orElse(throwable.getClass().getSimpleName())
                        : throwable.getClass().getSimpleName();
        return new FailureMapping(new AiFailure(type, retryable, errorCode), source);
    }

    @FunctionalInterface
    private interface ExceptionMappingRule {
        FailureMapping map(Throwable throwable);
    }
}
