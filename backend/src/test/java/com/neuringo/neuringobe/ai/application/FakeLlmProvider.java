package com.neuringo.neuringobe.ai.application;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.Objects;

final class FakeLlmProvider implements LlmProvider {

    private AiCallResult<LlmCompletion> nextResult;

    void willReturn(AiCallResult<LlmCompletion> result) {
        this.nextResult = Objects.requireNonNull(result);
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        if (nextResult == null) {
            throw new IllegalStateException("FakeLlmProvider result is not configured");
        }
        return nextResult;
    }
}
