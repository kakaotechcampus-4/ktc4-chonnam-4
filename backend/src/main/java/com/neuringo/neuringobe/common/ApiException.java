package com.neuringo.neuringobe.common;

import org.springframework.http.HttpStatus;

/**
 * 직접 정의한 업무 예외의 공통 부모. {@link GlobalExceptionHandler} 가 상태·코드·메시지를 그대로 공통 오류 형식으로 응답하므로, 메시지에는 문구를
 * 통제한 값과 식별자만 담는다(요청 원문·개인정보 금지).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
