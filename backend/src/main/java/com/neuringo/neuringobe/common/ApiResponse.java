package com.neuringo.neuringobe.common;

import java.util.UUID;

public record ApiResponse<T>(T data, ApiMeta meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, new ApiMeta(UUID.randomUUID().toString()));
    }
}
