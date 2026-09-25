package com.neuringo.neuringobe.common;

import java.util.List;

public record ApiErrorResponse(ApiError error) {

    public record ApiError(
            int status,
            String code,
            String message,
            String path,
            String traceId,
            List<FieldError> fieldErrors) {}

    // 입력 원문(rejectedValue)은 개인정보가 섞일 수 있어 담지 않는다.
    public record FieldError(String field, String message) {}

    public static ApiErrorResponse of(
            int status, String code, String message, String path, String traceId) {
        return of(status, code, message, path, traceId, List.of());
    }

    public static ApiErrorResponse of(
            int status,
            String code,
            String message,
            String path,
            String traceId,
            List<FieldError> fieldErrors) {
        return new ApiErrorResponse(
                new ApiError(status, code, message, path, traceId, fieldErrors));
    }
}
