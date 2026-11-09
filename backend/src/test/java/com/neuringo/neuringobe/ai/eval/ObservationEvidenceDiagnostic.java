package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 기존 A/B 입력으로 관찰 세 항목의 판정과 근거를 한 번씩 진단한다. */
public final class ObservationEvidenceDiagnostic {
    List<ObservationPairDiagnostic.Case> cases() {
        return new ObservationPairDiagnostic().cases();
    }

    Report run(LlmProvider provider, RoleplayTurnDeadline deadline, Consumer<Entry> completed) {
        var entries = new ArrayList<Entry>();
        String event = cases().getFirst().expectedReference();
        String stopped = null;
        for (var sample : cases()) {
            var result = new ObservationEvidenceReview(sample.source()).run(provider, deadline);
            var scores = new ArrayList<Score>();
            if (result.review() != null) {
                for (var review : result.review().path("reviews")) {
                    String ref = review.path("criterion_reference").asString();
                    String expected = ref.equals(event) ? "SUPPORTED" : "UNSUPPORTED";
                    String actual = review.path("verdict").asString();
                    scores.add(new Score(ref, expected, actual, expected.equals(actual)));
                }
            }
            boolean matches =
                    result.review() != null && scores.stream().allMatch(Score::verdictMatches);
            var entry = new Entry(sample.id(), result, List.copyOf(scores), matches);
            entries.add(entry);
            completed.accept(entry);
            if (!result.outcome().equals("VALID_DIAGNOSTIC_OUTPUT")) {
                stopped = result.outcome();
                break;
            }
        }
        return new Report(
                List.copyOf(entries),
                entries.stream().mapToInt(e -> e.result().physicalCalls()).sum(),
                entries.size() == 2 && entries.stream().allMatch(Entry::expectationMatches),
                stopped);
    }

    record Score(
            String criterionReference,
            String expectedVerdict,
            String actualVerdict,
            boolean verdictMatches) {}

    record Entry(
            String caseId,
            AnalysisReviewDiagnostic.Result result,
            List<Score> scores,
            boolean expectationMatches) {}

    record Report(
            List<Entry> cases,
            int physicalCalls,
            boolean discriminationSuccess,
            String stoppedReason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("승인된 고정 A/B 근거 진단만 실행합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        var diagnostic = new ObservationEvidenceDiagnostic();
        Path dir = RoleplayEvalMain.diagnosticOutput("observation-evidence", model);
        for (var sample : diagnostic.cases()) {
            Path caseDir = dir.resolve(sample.id());
            Files.createDirectory(caseDir);
            var request =
                    new ObservationEvidenceReview(sample.source())
                            .request(RoleplayTurnDeadline.start());
            Files.writeString(caseDir.resolve("request.json"), request.userPrompt());
            Files.writeString(caseDir.resolve("schema.json"), request.responseJsonSchema());
            Files.writeString(caseDir.resolve("system-prompt.md"), request.systemPrompt());
            Files.writeString(caseDir.resolve("source-fixture.json"), sample.source().toString());
        }
        Files.writeString(
                dir.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "baseline_pair_run",
                                "elice-p1-observation-pair-01",
                                "requested_model",
                                model,
                                "prompt_version",
                                ObservationEvidenceReview.PROMPT_VERSION,
                                "schema_version",
                                ObservationEvidenceReview.SCHEMA_VERSION,
                                "maximum_calls",
                                2,
                                "shared_budget_ms",
                                60000,
                                "sdk_max_retries",
                                0)));
        try (var context = RoleplayEvalMain.openAiContext(model)) {
            var report =
                    diagnostic.run(
                            context.getBean(LlmProvider.class),
                            RoleplayTurnDeadline.start(),
                            entry -> {
                                try {
                                    Files.writeString(
                                            dir.resolve(entry.caseId()).resolve("result.json"),
                                            EvalReport.JSON.writeValueAsString(entry));
                                } catch (IOException failure) {
                                    throw new java.io.UncheckedIOException(failure);
                                }
                            });
            Files.writeString(
                    dir.resolve("result.json"), EvalReport.JSON.writeValueAsString(report));
            System.out.printf(
                    "관찰별 근거 진단: calls=%d, discrimination=%s, stopped=%s%n결과: %s%n",
                    report.physicalCalls(),
                    report.discriminationSuccess(),
                    report.stoppedReason(),
                    dir.toAbsolutePath());
        }
    }
}
