package com.neuringo.neuringobe.ai.application.structured;

import static com.neuringo.neuringobe.ai.application.observation.RoleplayTiming.Stage.*;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.observation.RoleplayTiming;
import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StructuredLlmExecutor {

    private static final Logger log = LoggerFactory.getLogger(StructuredLlmExecutor.class);

    private final LlmProvider llmProvider;
    private final boolean observations;
    private final AiDiagnostics diagnostics;

    public StructuredLlmExecutor(LlmProvider llmProvider) {
        this(llmProvider, false);
    }

    public StructuredLlmExecutor(LlmProvider llmProvider, boolean observations) {
        this(llmProvider, observations, AiDiagnostics.NONE);
    }

    public StructuredLlmExecutor(
            LlmProvider llmProvider, boolean observations, AiDiagnostics diagnostics) {
        this.llmProvider = Objects.requireNonNull(llmProvider);
        this.observations = observations;
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    private void diagnostic(LlmRequest request, String outcome, String reason, Object result) {
        if (request == null || !diagnostics.active(request.traceContext().requestId())) return;
        var data =
                AiDiagnostics.fields(
                        "operation",
                        request.operation(),
                        "attempt",
                        request.currentAttempt(),
                        "outcome",
                        outcome,
                        "reason",
                        reason);
        if (result instanceof EvaluationResult evaluation) {
            data.put("decision", evaluation.decision());
            data.put("retry_target", evaluation.retryTarget());
            data.put("failure_codes", evaluation.failureCodes());
        }
        diagnostics.record(request.traceContext().requestId(), "VALIDATION", data);
    }

    private void observed(LlmRequest request, String outcome) {
        if (observations && request != null)
            log.info(
                    "LLM_VALIDATION request={} turn={} operation={} prompt={} schema={} attempt={} outcome={}",
                    request.traceContext().requestId(),
                    request.traceContext().turnId(),
                    request.operation(),
                    request.promptVersion(),
                    request.responseSchemaVersion(),
                    request.currentAttempt(),
                    outcome);
    }

    public <T> AiCallResult<T> execute(LlmRequest request, LlmOutputParser<T> parser) {
        AiCallResult<LlmCompletion> providerResult = llmProvider.complete(request);

        if (providerResult instanceof AiCallResult.Failure<?> failure) {
            observed(request, "PROVIDER_FAILURE_" + failure.failure().type().name());
            diagnostic(request, "PROVIDER_FAILURE", failure.failure().type().name(), null);
            return new AiCallResult.Failure<>(failure.failure(), failure.metadata());
        }

        AiCallResult.Success<?> success = (AiCallResult.Success<?>) providerResult;
        LlmCompletion completion = (LlmCompletion) success.data();

        try {
            T output =
                    RoleplayTiming.measure(
                            OUTPUT_VALIDATION, () -> parser.parse(completion.content()));
            observed(request, "VALID");
            diagnostic(request, "VALID", null, output);
            return new AiCallResult.Success<>(output, success.metadata());
        } catch (IndeterminateLlmOutputException exception) {
            observed(request, "INDETERMINATE_OUTPUT");
            diagnostic(request, "INDETERMINATE_OUTPUT", "INSUFFICIENT_CONTEXT", null);
            return new AiCallResult.Failure<>(
                    new AiFailure(AiFailureType.INDETERMINATE_OUTPUT, null), success.metadata());
        } catch (InvalidLlmOutputException exception) {
            observed(request, "INVALID_OUTPUT_FORMAT");
            diagnostic(request, "INVALID_OUTPUT_FORMAT", exception.getMessage(), null);
            // 예외 메시지는 원문을 포함할 수 있어 제한된 진단 파일에서만 확인한다.
            if (log.isDebugEnabled() && request != null) {
                log.debug(
                        "LLM_OUTPUT_REJECTED operation={} prompt={} attempt={} reason={}",
                        request.operation(),
                        request.promptVersion(),
                        request.currentAttempt(),
                        "INVALID_OUTPUT_FORMAT");
            }
            AiFailure failure = new AiFailure(AiFailureType.INVALID_OUTPUT_FORMAT, null);
            return new AiCallResult.Failure<>(failure, success.metadata());
        }
    }
}
