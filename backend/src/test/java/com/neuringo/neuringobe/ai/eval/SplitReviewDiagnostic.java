package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.ScopedAnalysisValidation;
import com.neuringo.neuringobe.ai.application.prompt.SplitReviewValidation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** 고정 분석 두 건을 먼저 검수하고 기대값 일치 시에만 후보를 한 번 검수한다. */
public final class SplitReviewDiagnostic {
    static final String ANALYSIS_VERSION = "roleplay-split-analysis-diagnostic/v1";
    static final String CANDIDATE_VERSION = "roleplay-split-candidate-diagnostic/v1";
    private static final Set<String> STOP =
            Set.of("PROVIDER_FAILURE", "SCHEMA_FAILURE", "TIMED_OUT", "EXECUTION_FAILURE");
    private final List<Sample> samples;
    private final String analysisPrompt = resource("split-analysis-prompt-v1.md");
    private final String candidatePrompt = resource("split-candidate-prompt-v1.md");

    SplitReviewDiagnostic() {
        samples =
                new ObservationPairDiagnostic()
                        .cases().stream()
                                .map(
                                        c ->
                                                new Sample(
                                                        c.id(),
                                                        c.source(),
                                                        new SplitReviewValidation(
                                                                c.source().path("input"))))
                                .toList();
    }

    List<Sample> samples() {
        return samples;
    }

    LlmRequest analysisRequest(Sample sample) {
        return request(
                sample,
                sample.validation().analysisInput(),
                sample.validation().analysisSchema(),
                analysisPrompt,
                ANALYSIS_VERSION,
                false);
    }

    private LlmRequest request(
            Sample sample,
            JsonNode payload,
            String schema,
            String prompt,
            String version,
            boolean candidate) {
        var original = sample.source().path("input");
        var session = UUID.nameUUIDFromBytes("split-review-p1".getBytes(StandardCharsets.UTF_8));
        // 기존 평가 요청의 필수 추적 칸에는 후보와 무관한 진단 식별자를 쓰며 모델 입력에는 보내지 않는다.
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        session,
                        null,
                        null,
                        null,
                        null,
                        session,
                        UUID.fromString(original.path("learner_turn").path("turn_id").asString()),
                        UUID.fromString(original.path("analysis_id").asString()),
                        candidate
                                ? UUID.fromString(
                                        original.path("candidate_response")
                                                .path("candidate_id")
                                                .asString())
                                : UUID.nameUUIDFromBytes(
                                        ("split-analysis-trace-" + sample.id())
                                                .getBytes(StandardCharsets.UTF_8)));
        return new LlmRequest(
                        trace,
                        new AiAttemptContext(0, 0, 1),
                        AiOperation.RESPONSE_EVALUATION,
                        1,
                        prompt,
                        payload.toString(),
                        version,
                        version.replace("roleplay-", ""),
                        null)
                .withResponseJsonSchema(schema);
    }

    Report run(LlmProvider provider, RoleplayTurnDeadline deadline) {
        return run(provider, deadline, ignored -> {}, ignored -> {});
    }

    Report run(
            LlmProvider provider,
            RoleplayTurnDeadline deadline,
            Consumer<StageRequest> before,
            Consumer<Entry> completed) {
        var entries = new ArrayList<Entry>();
        String stopped = null;
        for (var sample : samples) {
            var stage = new StageRequest(sample.id() + "-analysis", analysisRequest(sample));
            before.accept(stage);
            var entry =
                    call(
                            stage,
                            provider,
                            deadline,
                            raw -> {
                                var checks = sample.validation().validateAnalysis(raw);
                                return new Checked(
                                        checks.outcome(),
                                        checks.schemaValid(),
                                        checks,
                                        matchesExpected(sample.id(), checks),
                                        null,
                                        checks.reason());
                            });
            entries.add(entry);
            completed.accept(entry);
            if (STOP.contains(entry.outcome())) {
                stopped = entry.outcome();
                break;
            }
        }
        boolean gate =
                entries.size() == 2 && entries.stream().allMatch(Entry::analysisMatchesExpected);
        String skipped = gate ? null : "ANALYSIS_EXPECTATION_OR_VALIDATION_FAILED";
        if (gate) {
            var a = samples.getFirst();
            var candidate =
                    a.validation()
                            .candidateReview(
                                    entries.getFirst().rawOutput(),
                                    a.source().path("input").path("candidate_response"));
            var stage =
                    new StageRequest(
                            "A-candidate",
                            request(
                                    a,
                                    candidate.input(),
                                    candidate.schema(),
                                    candidatePrompt,
                                    CANDIDATE_VERSION,
                                    true));
            before.accept(stage);
            var entry =
                    call(
                            stage,
                            provider,
                            deadline,
                            raw -> {
                                if (!candidate.schemaValid(raw))
                                    return new Checked(
                                            "SCHEMA_FAILURE",
                                            false,
                                            null,
                                            false,
                                            null,
                                            "candidate review does not match schema");
                                try {
                                    return new Checked(
                                            "VALID_CANDIDATE_REVIEW",
                                            true,
                                            null,
                                            false,
                                            candidate.validate(raw),
                                            null);
                                } catch (InvalidLlmOutputException
                                        | IllegalArgumentException failure) {
                                    return new Checked(
                                            "INVALID_CANDIDATE_REVIEW",
                                            true,
                                            null,
                                            false,
                                            null,
                                            "candidate decision contradicts validation rules");
                                }
                            });
            entries.add(entry);
            completed.accept(entry);
            if (STOP.contains(entry.outcome())) stopped = entry.outcome();
        }
        return new Report(
                List.copyOf(entries),
                entries.stream().mapToInt(Entry::physicalCalls).sum(),
                gate,
                skipped,
                stopped);
    }

    private static boolean matchesExpected(String id, ScopedAnalysisValidation.Checks checks) {
        if (!"VALID_REVIEW".equals(checks.outcome())
                || !Boolean.TRUE.equals(checks.currentGoalSupported())
                || !Boolean.TRUE.equals(checks.activitySupported())
                || !Boolean.TRUE.equals(checks.interpretationStrategySupported())) return false;
        var verdicts =
                checks.observationVerdicts().stream()
                        .map(ScopedAnalysisValidation.ObservationVerdict::verdict)
                        .toList();
        return id.equals("A")
                ? Boolean.TRUE.equals(checks.observationsSupported())
                        && verdicts.equals(List.of("SUPPORTED"))
                : Boolean.FALSE.equals(checks.observationsSupported())
                        && verdicts.equals(List.of("SUPPORTED", "UNSUPPORTED"));
    }

    private Entry call(
            StageRequest stage,
            LlmProvider provider,
            RoleplayTurnDeadline deadline,
            Function<String, Checked> validate) {
        long start = System.nanoTime();
        var calls = new AtomicInteger();
        var raw = new AtomicReference<String>();
        var metadata = new AtomicReference<AiCallMetadata>();
        Checked checked;
        try {
            var result =
                    deadline.withinBudget(
                            () -> {
                                calls.incrementAndGet();
                                var response =
                                        provider.complete(
                                                stage.request()
                                                        .withCallBudget(deadline.callBudget()));
                                if (response
                                        instanceof AiCallResult.Success<LlmCompletion> success) {
                                    metadata.set(success.metadata());
                                    raw.set(success.data().content());
                                } else if (response
                                        instanceof AiCallResult.Failure<LlmCompletion> failure)
                                    metadata.set(failure.metadata());
                                return response;
                            });
            if (result instanceof AiCallResult.Failure<LlmCompletion> failure)
                checked =
                        new Checked(
                                "PROVIDER_FAILURE",
                                false,
                                null,
                                false,
                                null,
                                failure.failure().type().name());
            else {
                checked = validate.apply(raw.get());
                deadline.requireActive();
            }
        } catch (RoleplayTurnDeadline.Expired failure) {
            checked =
                    new Checked("TIMED_OUT", false, null, false, null, "shared deadline exceeded");
        } catch (RuntimeException failure) {
            checked =
                    new Checked(
                            "EXECUTION_FAILURE",
                            false,
                            null,
                            false,
                            null,
                            "diagnostic execution failed");
        }
        return new Entry(
                stage.id(),
                checked.outcome(),
                calls.get(),
                (System.nanoTime() - start) / 1_000_000,
                metadata.get(),
                raw.get(),
                checked.schemaValid(),
                checked.analysisChecks(),
                checked.analysisMatchesExpected(),
                checked.combinedResult(),
                checked.reason());
    }

    private static String resource(String name) {
        try (var stream = SplitReviewDiagnostic.class.getResourceAsStream("/eval/" + name)) {
            return new String(
                    java.util.Objects.requireNonNull(stream).readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    record Sample(String id, JsonNode source, SplitReviewValidation validation) {}

    record StageRequest(String id, LlmRequest request) {}

    record Checked(
            String outcome,
            boolean schemaValid,
            ScopedAnalysisValidation.Checks analysisChecks,
            boolean analysisMatchesExpected,
            EvaluationResult combinedResult,
            String reason) {}

    record Entry(
            String stage,
            String outcome,
            int physicalCalls,
            long totalMs,
            AiCallMetadata metadata,
            String rawOutput,
            boolean schemaValid,
            ScopedAnalysisValidation.Checks analysisChecks,
            boolean analysisMatchesExpected,
            EvaluationResult combinedResult,
            String reason) {}

    record Report(
            List<Entry> stages,
            int physicalCalls,
            boolean analysisPairMatchesExpected,
            String candidateSkippedReason,
            String stoppedReason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("승인된 고정 사례만 허용합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        Path output = RoleplayEvalMain.diagnosticOutput("split-review", model);
        var diagnostic = new SplitReviewDiagnostic();
        for (var sample : diagnostic.samples) {
            Files.writeString(
                    output.resolve(sample.id() + "-source-fixture.json"),
                    sample.source().toString());
            Files.writeString(
                    output.resolve(sample.id() + "-bindings.json"),
                    sample.validation().bindings().toString());
            saveRequest(
                    output,
                    new StageRequest(
                            sample.id() + "-analysis", diagnostic.analysisRequest(sample)));
        }
        Files.writeString(
                output.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "requested_model",
                                model,
                                "maximum_calls",
                                3,
                                "shared_budget_ms",
                                60000,
                                "sdk_max_retries",
                                0,
                                "source_resource",
                                ObservationPairDiagnostic.FIXTURE,
                                "git_commit",
                                String.valueOf(RoleplayEvalMain.gitCommit()),
                                "uncommitted_changes",
                                true,
                                "candidate_prompt_sha256",
                                IntegratedEvaluationDiagnostic.hash(diagnostic.candidatePrompt))));
        try (var context = RoleplayEvalMain.openAiContext(model)) {
            var report =
                    diagnostic.run(
                            context.getBean(LlmProvider.class),
                            RoleplayTurnDeadline.start(),
                            stage -> saveRequest(output, stage),
                            entry -> {
                                try {
                                    Files.writeString(
                                            output.resolve(entry.stage()).resolve("result.json"),
                                            EvalReport.JSON.writeValueAsString(entry));
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            });
            Files.writeString(
                    output.resolve("result.json"), EvalReport.JSON.writeValueAsString(report));
            System.out.printf(
                    "분리 검수 진단: calls=%d, analysis_pair_matches=%s, candidate_skipped=%s, stopped=%s%n결과: %s%n",
                    report.physicalCalls(),
                    report.analysisPairMatchesExpected(),
                    report.candidateSkippedReason(),
                    report.stoppedReason(),
                    output.toAbsolutePath());
        }
    }

    private static void saveRequest(Path output, StageRequest stage) {
        try {
            var dir = output.resolve(stage.id());
            Files.createDirectories(dir);
            var r = stage.request();
            Files.writeString(dir.resolve("request.json"), r.userPrompt());
            Files.writeString(dir.resolve("system-prompt.md"), r.systemPrompt());
            Files.writeString(dir.resolve("schema.json"), r.responseJsonSchema());
            Files.writeString(
                    dir.resolve("source.json"),
                    EvalReport.JSON.writeValueAsString(
                            Map.of(
                                    "request_sha256",
                                    IntegratedEvaluationDiagnostic.hash(r.userPrompt()),
                                    "prompt_sha256",
                                    IntegratedEvaluationDiagnostic.hash(r.systemPrompt()),
                                    "schema_sha256",
                                    IntegratedEvaluationDiagnostic.hash(r.responseJsonSchema()),
                                    "prompt_version",
                                    r.promptVersion(),
                                    "schema_version",
                                    r.responseSchemaVersion())));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
