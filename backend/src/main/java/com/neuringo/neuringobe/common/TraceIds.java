package com.neuringo.neuringobe.common;

import java.util.UUID;

/**
 * 응답 traceId 를 만드는 단일 지점. 지금은 요청마다 새 UUID 를 만든다.
 *
 * <p>TODO(S3-JEONG-02): 요청 단위 ID(Filter·MDC)로 바꾸고 {@code X-Trace-Id} 응답 헤더와 맞춘다. 여기만 고치면 성공 응답,
 * {@link GlobalExceptionHandler}, 인증 401(EntryPoint)이 함께 바뀐다(DEC-001 1.3절).
 */
public final class TraceIds {

    private TraceIds() {}

    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
