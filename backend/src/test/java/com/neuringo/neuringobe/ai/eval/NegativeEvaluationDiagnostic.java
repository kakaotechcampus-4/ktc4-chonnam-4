package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.node.ObjectNode;

/** 기존 운영 파서와 시도 제한으로 두 반례의 거절 이유와 복귀 위치를 확인한다. */
public final class NegativeEvaluationDiagnostic {
    private final IntegratedEvaluationDiagnostic delegate;

    NegativeEvaluationDiagnostic() {
        var cases = new ObservationPairDiagnostic().cases();
        var builder = new IntegratedEvaluationDiagnostic();
        var wrongAnalysis = cases.get(1).source().deepCopy();
        var wrongCandidate = cases.getFirst().source().deepCopy();
        ((ObjectNode) wrongCandidate.path("input").path("candidate_response"))
                .put("text", "나 발표하기 싫어서 일부러 멈췄어.");
        delegate =
                new IntegratedEvaluationDiagnostic(
                        List.of(
                                builder.prepare(
                                        new ObservationPairDiagnostic.Case(
                                                "A",
                                                wrongAnalysis,
                                                "UNSUPPORTED",
                                                "OBSERVATION_NOT_GROUNDED",
                                                cases.get(1).expectedReference())),
                                builder.prepare(
                                        new ObservationPairDiagnostic.Case(
                                                "B",
                                                wrongCandidate,
                                                "SUPPORTED",
                                                "NONE",
                                                cases.getFirst().expectedReference()))));
    }

    List<IntegratedEvaluationDiagnostic.Sample> samples() {
        return delegate.samples();
    }

    Report run(
            LlmProvider provider,
            RoleplayTurnDeadline deadline,
            Consumer<IntegratedEvaluationDiagnostic.Entry> completed) {
        var report = delegate.run(provider, deadline, completed);
        return new Report(
                report,
                report.cases().size() == 2
                        && report.cases().stream()
                                .allMatch(NegativeEvaluationDiagnostic::expectedRejection));
    }

    static boolean expectedRejection(IntegratedEvaluationDiagnostic.Entry entry) {
        if (!"ACCEPTED".equals(entry.outcome())
                || entry.acceptedResult() == null
                || entry.acceptedResult().canDeliver()) return false;
        var result = entry.acceptedResult();
        var raw = EvalReport.JSON.readTree(entry.rawOutput());
        if (entry.caseId().equals("A")) {
            return result.decision().name().equals("REANALYZE")
                    && !result.goalObservationReview().observationsSupported()
                    && raw.path("observation_reviews")
                            .path("o0")
                            .path("verdict")
                            .asString()
                            .equals("SUPPORTED")
                    && raw.path("observation_reviews")
                            .path("o1")
                            .path("verdict")
                            .asString()
                            .equals("UNSUPPORTED")
                    && List.of(
                                    "current_goal_review",
                                    "activity_review",
                                    "interpretation_strategy_review")
                            .stream()
                            .allMatch(
                                    k ->
                                            raw.path(k)
                                                    .path("verdict")
                                                    .asString()
                                                    .equals("SUPPORTED"));
        }
        return Set.of("REGENERATE", "SAFETY_REGENERATE").contains(result.decision().name())
                && result.goalObservationReview().supportsAnalysis()
                && !raw.path("checks").path("scenario_consistent").asBoolean()
                && result.failureCodes().stream()
                        .anyMatch(
                                Set.of("SCENARIO_CONTRADICTION", "PROHIBITED_INFERENCE")::contains);
    }

    record Report(
            IntegratedEvaluationDiagnostic.Report execution, boolean bothExpectedRejections) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("고정된 두 반례만 허용합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        var diagnostic = new NegativeEvaluationDiagnostic();
        Path output = RoleplayEvalMain.diagnosticOutput("negative-evaluation", model);
        for (var sample : diagnostic.samples()) {
            Path dir = output.resolve(sample.id());
            Files.createDirectory(dir);
            var r = sample.prepared().request();
            Files.writeString(dir.resolve("source-fixture.json"), sample.source().toString());
            Files.writeString(dir.resolve("request.json"), r.userPrompt());
            Files.writeString(dir.resolve("schema.json"), r.responseJsonSchema());
            Files.writeString(dir.resolve("system-prompt.md"), r.systemPrompt());
            Files.writeString(
                    dir.resolve("source.json"),
                    EvalReport.JSON.writeValueAsString(
                            Map.of(
                                    "request_sha256",
                                    IntegratedEvaluationDiagnostic.hash(r.userPrompt()),
                                    "schema_sha256",
                                    IntegratedEvaluationDiagnostic.hash(r.responseJsonSchema()),
                                    "prompt_sha256",
                                    IntegratedEvaluationDiagnostic.hash(r.systemPrompt()),
                                    "expected",
                                    sample.id().equals("A")
                                            ? "REANALYZE_FALSE_EMOTION_ONLY"
                                            : "REGENERATE_PROHIBITED_INFERENCE")));
        }
        Files.writeString(
                output.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "model",
                                model,
                                "reasoning_effort",
                                RoleplayEvalMain.env("AI_REASONING_EFFORT", "unset"),
                                "timeout",
                                RoleplayEvalMain.env("AI_TIMEOUT", "10s"),
                                "maximum_calls",
                                2,
                                "shared_budget_ms",
                                60000,
                                "sdk_max_retries",
                                0,
                                "analysis_calls",
                                0,
                                "generation_calls",
                                0,
                                "git_commit",
                                String.valueOf(RoleplayEvalMain.gitCommit()))));
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
                    "반례 진단 calls=%d expected_rejections=%s stopped=%s%n결과: %s%n",
                    report.execution().physicalCalls(),
                    report.bothExpectedRejections(),
                    report.execution().stoppedReason(),
                    output.toAbsolutePath());
        }
    }
}
