package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 측정용 LlmProvider 래퍼. 호출마다 단계·지연·토큰·실패 유형을 남긴다.
 *
 * <p>모델 출력 원문(content)은 형식 실패 샘플을 모으려고 메모리에만 들고 있다. 보고서에는 형식 실패한 호출의 원문만 쓴다. 회귀 세트의 아동 발화는 모두 합성
 * 문장이다.
 */
final class RecordingLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final List<Call> calls = new ArrayList<>();

    RecordingLlmProvider(LlmProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        long startedAt = System.nanoTime();
        AiCallResult<LlmCompletion> result = delegate.complete(request);
        long wallMs = (System.nanoTime() - startedAt) / 1_000_000;
        Call call =
                switch (result) {
                    case AiCallResult.Success<LlmCompletion> success ->
                            Call.of(
                                    request,
                                    wallMs,
                                    success.metadata(),
                                    null,
                                    success.data().content());
                    case AiCallResult.Failure<LlmCompletion> failure ->
                            Call.of(
                                    request,
                                    wallMs,
                                    failure.metadata(),
                                    failure.failure().type(),
                                    null);
                };
        synchronized (calls) {
            calls.add(call);
        }
        return result;
    }

    int size() {
        synchronized (calls) {
            return calls.size();
        }
    }

    /** from 번째 이후에 남은 호출. 한 턴의 호출만 떼어 볼 때 쓴다. */
    List<Call> since(int from) {
        synchronized (calls) {
            return List.copyOf(calls.subList(from, calls.size()));
        }
    }

    /**
     * 제공자 호출 한 번.
     *
     * @param failureType 제공자 단계 실패(타임아웃·빈 출력 등). 형식 실패는 파싱 단계에서 나므로 여기서는 null 이고 {@link
     *     ObservedTurnSteps} 가 따로 센다.
     */
    record Call(
            AiOperation operation,
            int attempt,
            long wallMs,
            Integer inputTokens,
            Integer outputTokens,
            String finishReason,
            String model,
            AiFailureType failureType,
            String content) {

        static Call of(
                LlmRequest request,
                long wallMs,
                AiCallMetadata metadata,
                AiFailureType failureType,
                String content) {
            return new Call(
                    request.operation(),
                    request.currentAttempt(),
                    wallMs,
                    metadata.inputTokens(),
                    metadata.outputTokens(),
                    metadata.finishReason(),
                    metadata.model(),
                    failureType,
                    content);
        }

        int tokens() {
            return (inputTokens == null ? 0 : inputTokens)
                    + (outputTokens == null ? 0 : outputTokens);
        }
    }
}
