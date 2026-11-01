package com.neuringo.neuringobe.ai.application.structured;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StructuredLlmExecutor {

    private static final Logger log = LoggerFactory.getLogger(StructuredLlmExecutor.class);

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
            // 출력 검사 메시지는 어느 검사에서 걸렸는지만 담은 고정 문장이다(모델 출력·아동 발화는 담지 않는다).
            // 원인 문장(cause)은 모델 출력 조각을 담을 수 있어 남기지 않는다.
            log.debug(
                    "LLM_OUTPUT_REJECTED operation={} prompt={} attempt={} reason={}",
                    request.operation(),
                    request.promptVersion(),
                    request.currentAttempt(),
                    exception.getMessage());
            AiFailure failure = new AiFailure(AiFailureType.INVALID_OUTPUT_FORMAT, null);
            return new AiCallResult.Failure<>(failure, success.metadata());
        }
    }
}
