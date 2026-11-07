package com.neuringo.neuringobe.common;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 429 Too Many Requests. 응답에 {@code Retry-After}(초, 올림)를 붙여 화면이 언제 다시 보내면 되는지 알게 한다. 메시지는 {@link
 * ApiException} 과 같이 문구를 통제한 값만 담는다.
 */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String message, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        long millis = Math.max(0, retryAfter.toMillis());
        this.retryAfterSeconds = Math.max(1, (millis + 999) / 1000);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
