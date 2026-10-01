package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import java.util.Objects;

/** StructuredLlmExecutor.execute(request, parser) 에 그대로 넘기는 한 쌍. parser 는 이 요청의 ID 와 맞는지도 검사한다. */
public record PreparedPrompt<T>(LlmRequest request, LlmOutputParser<T> parser) {

    public PreparedPrompt {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(parser, "parser must not be null");
    }
}
