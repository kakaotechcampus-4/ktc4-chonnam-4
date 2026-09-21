package com.neuringo.neuringobe.common;

import java.util.List;
import java.util.UUID;

public record ApiErrorResponse(ApiError error) {

    public record ApiError(
            int status,
            String code,
            String message,
            String path,
            String traceId,
            List<String> fieldErrors) {}

    public static ApiErrorResponse of(int status, String code, String message, String path) {
        return new ApiErrorResponse(
                new ApiError(status, code, message, path, UUID.randomUUID().toString(), List.of()));
    }
}
