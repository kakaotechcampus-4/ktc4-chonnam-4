package com.neuringo.neuringobe.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 원인별 코드가 따로 없는 오류에 HTTP 상태만으로 붙이는 기본 코드·문구. {@link GlobalExceptionHandler}(Spring 표준 예외)와 {@link
 * ApiErrorController}(필터 단계 예외)가 같이 써서, 같은 상태는 어느 경로로 와도 같은 코드로 응답한다.
 */
final class StatusErrors {

    private StatusErrors() {}

    // 정본(05_API_명세 §0.4)에 있는 코드는 그대로 쓰고, 없는 상태(405 등)는 HTTP 상태 이름을 쓴다.
    // 400 을 원인별(MALFORMED_JSON 등)로 나누는 것은 후속 작업이다.
    static String codeOf(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 500 -> "INTERNAL_ERROR";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> {
                HttpStatus status = HttpStatus.resolve(statusCode.value());
                yield status != null ? status.name() : "HTTP_" + statusCode.value();
            }
        };
    }

    static String messageOf(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> "요청 형식이 올바르지 않습니다.";
            case 404 -> "요청한 리소스를 찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청 메서드입니다.";
            case 415 -> "지원하지 않는 Content-Type 입니다.";
            case 500 -> "서버 내부 오류가 발생했습니다.";
            default -> "요청을 처리할 수 없습니다.";
        };
    }
}
