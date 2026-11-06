package com.neuringo.neuringobe.ai.infrastructure.springai;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 호출 목적(AiOperation)에 따라 다른 모델로 보낸다. 경로가 없는 목적은 기본 제공자로 간다. 역할극 턴 호출은 경로를 두지 않아 지금처럼 기본 제공자를 쓴다.
 */
public final class OperationRoutingLlmProvider implements LlmProvider {
    private final LlmProvider fallback;
    private final Map<AiOperation, LlmProvider> routes;

    public OperationRoutingLlmProvider(LlmProvider fallback, Map<AiOperation, LlmProvider> routes) {
        this.fallback = Objects.requireNonNull(fallback);
        this.routes = routes.isEmpty() ? Map.of() : new EnumMap<>(routes);
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        return routes.getOrDefault(request.operation(), fallback).complete(request);
    }
}
