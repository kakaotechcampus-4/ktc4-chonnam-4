package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.ScopedAnalysisValidation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 고정 관찰 키와 목표 범위를 사용해 기존 A/B를 두 번만 검수한다. */
public final class ScopedReviewDiagnostic {
    static final String VERSION = "roleplay-scoped-analysis-diagnostic/v1";
    static final String SCHEMA_VERSION = "scoped-analysis-diagnostic/v1";
    private final List<Sample> samples;

    ScopedReviewDiagnostic() {
        String prompt;
        try (var stream = getClass().getResourceAsStream("/eval/scoped-analysis-prompt-v1.md")) {
            prompt =
                    new String(
                            java.util.Objects.requireNonNull(stream).readAllBytes(),
                            StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        var result = new ArrayList<Sample>();
        for (var baseline : new IntegratedEvaluationDiagnostic().samples()) {
            var validation = new ScopedAnalysisValidation(baseline.source().path("input"));
            var original = baseline.prepared().request();
            var request =
                    new LlmRequest(
                                    original.traceContext(),
                                    original.attemptContext(),
                                    original.operation(),
                                    1,
                                    prompt,
                                    validation.input().toString(),
                                    VERSION,
                                    SCHEMA_VERSION,
                                    null)
                            .withResponseJsonSchema(validation.schema());
            result.add(new Sample(baseline.id(), request, validation, baseline.source()));
        }
        samples = List.copyOf(result);
    }

    List<Sample> samples() {
        return samples;
    }

    Report run(LlmProvider provider, RoleplayTurnDeadline deadline, Consumer<Entry> completed) {
        var entries = new ArrayList<Entry>();
        String stopped = null;
        for (var sample : samples) {
            var entry = call(sample, provider, deadline);
            entries.add(entry);
            completed.accept(entry);
            if (Set.of("PROVIDER_FAILURE", "TIMED_OUT", "EXECUTION_FAILURE", "SCHEMA_FAILURE")
                    .contains(entry.outcome())) {
                stopped = entry.outcome();
                break;
            }
        }
        return new Report(
                List.copyOf(entries),
                entries.stream().mapToInt(Entry::physicalCalls).sum(),
                stopped);
    }

    private Entry call(Sample sample, LlmProvider provider, RoleplayTurnDeadline deadline) {
        long started = System.nanoTime();
        var calls = new AtomicInteger();
        AiCallMetadata metadata = null;
        String raw = null;
        try {
            var result =
                    deadline.withinBudget(
                            () -> {
                                calls.incrementAndGet();
                                return provider.complete(
                                        sample.request().withCallBudget(deadline.callBudget()));
                            });
            if (result instanceof AiCallResult.Failure<LlmCompletion> failure) {
                return new Entry(
                        sample.id(),
                        "PROVIDER_FAILURE",
                        calls.get(),
                        elapsed(started),
                        failure.metadata(),
                        null,
                        null,
                        failure.failure().type().name());
            }
            var success = (AiCallResult.Success<LlmCompletion>) result;
            metadata = success.metadata();
            raw = success.data().content();
            var checks = sample.validation().validate(raw);
            deadline.requireActive();
            return new Entry(
                    sample.id(),
                    checks.outcome(),
                    calls.get(),
                    elapsed(started),
                    metadata,
                    raw,
                    checks,
                    checks.reason());
        } catch (RoleplayTurnDeadline.Expired failure) {
            return new Entry(
                    sample.id(),
                    "TIMED_OUT",
                    calls.get(),
                    elapsed(started),
                    metadata,
                    raw,
                    null,
                    "shared deadline exceeded");
        } catch (RuntimeException failure) {
            return new Entry(
                    sample.id(),
                    "EXECUTION_FAILURE",
                    calls.get(),
                    elapsed(started),
                    metadata,
                    raw,
                    null,
                    "diagnostic execution failed");
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    record Sample(
            String id,
            LlmRequest request,
            ScopedAnalysisValidation validation,
            tools.jackson.databind.JsonNode source) {}

    record Entry(
            String caseId,
            String outcome,
            int physicalCalls,
            long totalMs,
            AiCallMetadata metadata,
            String rawOutput,
            ScopedAnalysisValidation.Checks checks,
            String reason) {}

    record Report(List<Entry> cases, int physicalCalls, String stoppedReason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("승인된 고정 두 조건만 허용합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        Path output = RoleplayEvalMain.diagnosticOutput("scoped-review", model);
        var diagnostic = new ScopedReviewDiagnostic();
        for (var sample : diagnostic.samples()) {
            var dir = output.resolve(sample.id());
            Files.createDirectory(dir);
            var request = sample.request();
            Files.writeString(dir.resolve("source-fixture.json"), sample.source().toString());
            Files.writeString(
                    dir.resolve("bindings.json"), sample.validation().bindings().toString());
            Files.writeString(dir.resolve("request.json"), request.userPrompt());
            Files.writeString(dir.resolve("system-prompt.md"), request.systemPrompt());
            Files.writeString(dir.resolve("schema.json"), request.responseJsonSchema());
            Files.writeString(
                    dir.resolve("source.json"),
                    EvalReport.JSON.writeValueAsString(
                            Map.of(
                                    "request_sha256",
                                            IntegratedEvaluationDiagnostic.hash(
                                                    request.userPrompt()),
                                    "prompt_sha256",
                                            IntegratedEvaluationDiagnostic.hash(
                                                    request.systemPrompt()),
                                    "schema_sha256",
                                            IntegratedEvaluationDiagnostic.hash(
                                                    request.responseJsonSchema()))));
        }
        Files.writeString(
                output.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "requested_model",
                                model,
                                "prompt_version",
                                VERSION,
                                "schema_version",
                                SCHEMA_VERSION,
                                "maximum_calls",
                                2,
                                "shared_budget_ms",
                                60000,
                                "sdk_max_retries",
                                0,
                                "source_resource",
                                ObservationPairDiagnostic.FIXTURE,
                                "baseline_run",
                                "elice-p1-review-isolation-01",
                                "git_commit",
                                String.valueOf(RoleplayEvalMain.gitCommit()),
                                "uncommitted_changes",
                                true)));
        try (var context = RoleplayEvalMain.openAiContext(model)) {
            var report =
                    diagnostic.run(
                            context.getBean(LlmProvider.class),
                            RoleplayTurnDeadline.start(),
                            entry -> {
                                try {
                                    Files.writeString(
                                            output.resolve(entry.caseId()).resolve("result.json"),
                                            EvalReport.JSON.writeValueAsString(entry));
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            });
            Files.writeString(
                    output.resolve("result.json"), EvalReport.JSON.writeValueAsString(report));
            System.out.printf(
                    "범위 고정 분석 검수: calls=%d, stopped=%s%n결과: %s%n",
                    report.physicalCalls(), report.stoppedReason(), output.toAbsolutePath());
        }
    }
}
