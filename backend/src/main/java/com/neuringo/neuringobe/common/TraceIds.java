package com.neuringo.neuringobe.common;

import java.util.UUID;
import org.slf4j.MDC;

/** 관측이 활성화된 역할극 요청은 응답·헤더·AI 호출에 동일한 식별자를 사용한다. */
public final class TraceIds {

    public static final String REQUEST_KEY = "neuringo.request_id";

    private TraceIds() {}

    public static String newTraceId() {
        String requestId = MDC.get(REQUEST_KEY);
        if (requestId != null) {
            try {
                return UUID.fromString(requestId).toString();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return UUID.randomUUID().toString();
    }
}
