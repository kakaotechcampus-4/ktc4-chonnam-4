package com.neuringo.neuringobe.ai.application.port;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 원문 진단은 HTTP에서 허용된 요청만 기록하며 기본 구현은 수집하지 않는다. */
public interface AiDiagnostics {
    AiDiagnostics NONE = new AiDiagnostics() {};

    default void begin(UUID requestId) {}

    default boolean active(UUID requestId) {
        return false;
    }

    default void record(UUID requestId, String event, Map<String, Object> data) {}

    default void end(UUID requestId, Map<String, Object> data) {}

    static Map<String, Object> fields(Object... pairs) {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
}
