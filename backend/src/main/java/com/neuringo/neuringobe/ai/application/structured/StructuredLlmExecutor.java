package com.neuringo.neuringobe.ai.application.structured;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.Objects;

public final class StructuredLlmExecutor {

    private final LlmProvider llmProvider;

    public StructuredLlmExecutor(LlmProvider llmProvider) {
        this.llmProvider = Objects.requireNonNull(llmProvider);
    }

    public <T> AiCallResult<T> execute(LlmRequest request, LlmOutputParser<T> parser) {
        AiCallResult<LlmCompletion> providerResult = llmProvider.complete(request);

        if (providerResult instanceof AiCallResult.Failure<?> failure) {
            return new AiCallResult.Failure<>(failure.failure(), failure.metadata());
        }

        AiCallResult.Success<?> success = (AiCallResult.Success<?>) providerResult;
        LlmCompletion completion = (LlmCompletion) success.data();

        try {
            T output = parser.parse(completion.content());
            return new AiCallResult.Success<>(output, success.metadata());
        } catch (InvalidLlmOutputException exception) {
            AiFailure failure = new AiFailure(AiFailureType.INVALID_OUTPUT_FORMAT, true, null);
            return new AiCallResult.Failure<>(failure, success.metadata());
        }
    }
}
