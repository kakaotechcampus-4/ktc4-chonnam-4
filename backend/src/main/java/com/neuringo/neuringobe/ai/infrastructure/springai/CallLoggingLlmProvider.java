package com.neuringo.neuringobe.ai.infrastructure.springai;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 호출 한 번마다 목적·결과·종료 이유·토큰 수만 한 줄로 남긴다(neuringo.ai.call-log=true). 프롬프트와 응답 글은 남기지 않는다. 출력 상한에 걸려
 * JSON 이 잘리는지(finishReason=length) 같은 운영 문제를 글 내용 없이 볼 때 쓴다.
 */
public final class CallLoggingLlmProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(CallLoggingLlmProvider.class);

    private final LlmProvider delegate;

    public CallLoggingLlmProvider(LlmProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        var result = delegate.complete(request);
        switch (result) {
            case AiCallResult.Success<LlmCompletion> success ->
                    line(request, "OK", success.metadata());
            case AiCallResult.Failure<LlmCompletion> failure ->
                    line(request, failure.failure().type().name(), failure.metadata());
        }
        return result;
    }

    private static void line(LlmRequest request, String outcome, AiCallMetadata meta) {
        log.info(
                "LLM_CALL operation={} outcome={} model={} attempt={} finish={} in={} out={} ms={}",
                request.operation(),
                outcome,
                meta.model(),
                meta.attemptNo(),
                meta.finishReason(),
                meta.inputTokens(),
                meta.outputTokens(),
                meta.elapsedMs());
    }
}
