package com.neuringo.neuringobe.common;

public record ApiResponse<T>(T data, ApiMeta meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, new ApiMeta(TraceIds.newTraceId()));
    }
}
