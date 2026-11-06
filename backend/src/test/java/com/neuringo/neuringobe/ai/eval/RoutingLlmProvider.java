package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.Objects;

/** 평가 호출(REPORT_REVIEW)만 다른 모델로 보낸다. 운영 제공자는 아직 모델 하나라서 측정에서만 나눠 본다. */
final class RoutingLlmProvider implements LlmProvider {

    private final LlmProvider generation;
    private final LlmProvider review;

    RoutingLlmProvider(LlmProvider generation, LlmProvider review) {
        this.generation = Objects.requireNonNull(generation);
        this.review = Objects.requireNonNull(review);
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        return request.operation() == AiOperation.REPORT_REVIEW
                ? review.complete(request)
                : generation.complete(request);
    }
}
