package com.neuringo.neuringobe.ai.infrastructure.diagnostics;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;

/** 인증 헤더나 제공자 설정 대신 실제 모델 메시지와 응답 내용만 기록한다. */
public final class DiagnosticLlmProvider implements LlmProvider {
    private final LlmProvider delegate;
    private final AiDiagnostics diagnostics;

    public DiagnosticLlmProvider(LlmProvider delegate, AiDiagnostics diagnostics) {
        this.delegate = delegate;
        this.diagnostics = diagnostics;
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        var id = request.traceContext().requestId();
        if (!diagnostics.active(id)) return delegate.complete(request);
        diagnostics.record(
                id,
                "MODEL_REQUEST",
                AiDiagnostics.fields(
                        "operation",
                        request.operation(),
                        "attempt",
                        request.currentAttempt(),
                        "analysis_id",
                        request.traceContext().analysisId(),
                        "candidate_id",
                        request.traceContext().candidateId(),
                        "prompt_version",
                        request.promptVersion(),
                        "schema_version",
                        request.responseSchemaVersion(),
                        "system_prompt",
                        request.systemPrompt(),
                        "user_prompt",
                        request.userPrompt(),
                        "response_schema",
                        request.responseJsonSchema()));
        try {
            var result = delegate.complete(request);
            if (result instanceof AiCallResult.Success<LlmCompletion> success)
                diagnostics.record(
                        id,
                        "MODEL_RESPONSE",
                        AiDiagnostics.fields(
                                "operation",
                                request.operation(),
                                "attempt",
                                request.currentAttempt(),
                                "output",
                                success.data().content(),
                                "metadata",
                                success.metadata()));
            else if (result instanceof AiCallResult.Failure<LlmCompletion> failure)
                diagnostics.record(
                        id,
                        "MODEL_RESPONSE",
                        AiDiagnostics.fields(
                                "operation",
                                request.operation(),
                                "attempt",
                                request.currentAttempt(),
                                "failure",
                                failure.failure().type(),
                                "metadata",
                                failure.metadata()));
            return result;
        } catch (RuntimeException failure) {
            diagnostics.record(
                    id,
                    "MODEL_RESPONSE",
                    AiDiagnostics.fields(
                            "operation", request.operation(), "failure", "EXECUTION_EXCEPTION"));
            throw failure;
        }
    }
}
