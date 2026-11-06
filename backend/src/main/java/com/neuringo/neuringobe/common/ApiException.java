package com.neuringo.neuringobe.common;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 직접 정의한 업무 예외의 공통 부모. {@link GlobalExceptionHandler} 가 상태·코드·메시지를 그대로 공통 오류 형식으로 응답하므로, 메시지에는 문구를
 * 통제한 값과 식별자만 담는다(요청 원문·개인정보 금지).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiErrorResponse.FieldError> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    /** 입력의 어느 칸이 문제인지 화면에 알려야 할 때. 필드 메시지도 문구를 통제한 값만 담는다. */
    public ApiException(
            HttpStatus status,
            String code,
            String message,
            List<ApiErrorResponse.FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ApiErrorResponse.FieldError> getFieldErrors() {
        return fieldErrors;
    }

    /** 오류 응답에 함께 보낼 헤더. 기본은 없다. 429 의 Retry-After 처럼 필요한 하위 클래스가 덮어쓴다. */
    public HttpHeaders getHeaders() {
        return new HttpHeaders();
    }
}
