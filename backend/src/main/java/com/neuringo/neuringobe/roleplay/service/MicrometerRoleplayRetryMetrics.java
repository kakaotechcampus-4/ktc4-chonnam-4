package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRetryMetrics;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Finite labels only: no request/child/session IDs, text or provider error messages. */
@Component
public final class MicrometerRoleplayRetryMetrics implements RoleplayRetryMetrics {
    private final MeterRegistry registry;

    public MicrometerRoleplayRetryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void call(AiOperation operation, String outcome, long elapsedNanos) {
        registry.timer("roleplay.llm.stage", "operation", operation.name(), "outcome", outcome)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void failed(AiOperation operation, AiFailureType failure, boolean retryAllowed) {
        registry.counter(
                        "roleplay.llm.failure",
                        "operation",
                        operation.name(),
                        "failure",
                        failure.name(),
                        "retry_allowed",
                        Boolean.toString(retryAllowed))
                .increment();
    }

    @Override
    public void rejected(EvaluationDecision decision) {
        registry.counter("roleplay.llm.rejected", "decision", decision.name()).increment();
    }

    @Override
    public void finished(
            Completion completion,
            int analysis,
            int generation,
            int evaluation,
            int candidates,
            long elapsedNanos) {
        String outcome = completion.name();
        registry.timer(
                        "roleplay.llm.turn",
                        "outcome",
                        outcome,
                        "calls",
                        Integer.toString(analysis + generation + evaluation))
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        registry.summary("roleplay.llm.calls", "operation", "CAUSE_ANALYSIS", "outcome", outcome)
                .record(analysis);
        registry.summary(
                        "roleplay.llm.calls",
                        "operation",
                        "RESPONSE_GENERATION",
                        "outcome",
                        outcome)
                .record(generation);
        registry.summary(
                        "roleplay.llm.calls",
                        "operation",
                        "RESPONSE_EVALUATION",
                        "outcome",
                        outcome)
                .record(evaluation);
        registry.summary("roleplay.llm.candidates", "outcome", outcome).record(candidates);
    }
}
