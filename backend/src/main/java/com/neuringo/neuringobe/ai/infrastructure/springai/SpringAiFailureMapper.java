package com.neuringo.neuringobe.ai.infrastructure.springai;

import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

public final class SpringAiFailureMapper {

    public AiFailure map(Throwable throwable) {
        if (containsTimeout(throwable)) {
            return failure(AiFailureType.TIMEOUT, true, throwable);
        }

        Throwable current = throwable;
        while (current != null) {
            AiFailure failure = mapSingle(current);
            if (failure != null) {
                return failure;
            }
            current = current.getCause();
        }

        return failure(AiFailureType.UNKNOWN, false, throwable);
    }

    private boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            String typeName = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = current.getMessage();
            String normalizedMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (typeName.contains("timeout")
                    || normalizedMessage.contains("timeout")
                    || normalizedMessage.contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AiFailure mapSingle(Throwable throwable) {
        if (throwable instanceof OpenAIInvalidDataException) {
            return failure(AiFailureType.PROVIDER_RESPONSE_ERROR, true, throwable);
        }
        if (throwable instanceof OpenAIIoException ioException) {
            String message = ioException.getMessage();
            if (message != null) {
                String normalizedMessage = message.toLowerCase(Locale.ROOT);
                if (normalizedMessage.contains("timeout")
                        || normalizedMessage.contains("timed out")) {
                    return failure(AiFailureType.TIMEOUT, true, throwable);
                }
            }
            return failure(AiFailureType.NETWORK_ERROR, true, throwable);
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            return mapStatusCode(serviceException.statusCode(), throwable);
        }
        if (throwable instanceof HttpTimeoutException
                || throwable instanceof HttpConnectTimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof TimeoutException) {
            return failure(AiFailureType.TIMEOUT, true, throwable);
        }
        if (throwable instanceof ConnectException) {
            return failure(AiFailureType.NETWORK_ERROR, true, throwable);
        }

        String typeName = throwable.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (typeName.contains("timeout")) {
            return failure(AiFailureType.TIMEOUT, true, throwable);
        }
        if (typeName.contains("ratelimit") || typeName.contains("toomanyrequests")) {
            return failure(AiFailureType.RATE_LIMITED, true, throwable);
        }
        if (typeName.contains("authentication")
                || typeName.contains("unauthorized")
                || typeName.contains("permissiondenied")
                || typeName.contains("forbidden")) {
            return failure(AiFailureType.AUTHENTICATION_ERROR, false, throwable);
        }
        if (typeName.contains("internalserver")
                || typeName.contains("serviceunavailable")
                || typeName.contains("badgateway")) {
            return failure(AiFailureType.PROVIDER_UNAVAILABLE, true, throwable);
        }
        if (typeName.contains("json")
                || typeName.contains("decode")
                || typeName.contains("parse")) {
            return failure(AiFailureType.PROVIDER_RESPONSE_ERROR, true, throwable);
        }
        if (typeName.contains("connect") || typeName.contains("network")) {
            return failure(AiFailureType.NETWORK_ERROR, true, throwable);
        }
        return null;
    }

    private AiFailure mapStatusCode(int statusCode, Throwable throwable) {
        if (statusCode == 401 || statusCode == 403) {
            return failure(AiFailureType.AUTHENTICATION_ERROR, false, throwable);
        }
        if (statusCode == 429) {
            return failure(AiFailureType.RATE_LIMITED, true, throwable);
        }
        if (statusCode >= 500) {
            return failure(AiFailureType.PROVIDER_UNAVAILABLE, true, throwable);
        }
        return failure(AiFailureType.PROVIDER_RESPONSE_ERROR, false, throwable);
    }

    private AiFailure failure(AiFailureType type, boolean retryable, Throwable throwable) {
        return new AiFailure(type, retryable, throwable.getClass().getSimpleName());
    }
}
