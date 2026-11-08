package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** 고정 입력의 관찰 하나만 바꿔 최대 두 번 검수한다. 기대값은 모델에 보내지 않는다. */
public final class ObservationPairDiagnostic {
    static final String FIXTURE = "eval/analysis-review-observation-pair-p1-v1.json";

    private final JsonNode frozen;

    ObservationPairDiagnostic() {
        try (var stream = getClass().getResourceAsStream("/" + FIXTURE)) {
            frozen =
                    EvalReport.JSON.readTree(
                            new String(
                                    Objects.requireNonNull(stream).readAllBytes(),
                                    StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    List<Case> cases() {
        var a = frozen.deepCopy();
        var b = frozen.deepCopy();
        var input = b.path("input");
        var analysis = input.path("analysis_result");
        String target = analysis.path("next_strategy").path("target_micro_goal_id").asString();
        JsonNode emotion = null;
        for (var criterion : input.path("goal_observation_context").path("approved_criteria")) {
            if (criterion.path("micro_goal_id").asString().equals(target)) emotion = criterion;
        }
        Objects.requireNonNull(emotion, "추가할 감정 관찰의 승인 기준이 필요합니다.");
        var observation = EvalReport.JSON.createObjectNode();
        observation
                .putObject("reference")
                .put("turn_id", input.path("learner_turn").path("turn_id").asString())
                .put("micro_goal_id", target)
                .put("criterion_reference", emotion.path("reference").asString());
        observation.put("status", "EXPLICIT");
        ((ArrayNode) analysis.path("goal_observation").path("observations")).add(observation);
        String eventReference =
                a.path("input")
                        .path("analysis_result")
                        .path("goal_observation")
                        .path("observations")
                        .get(0)
                        .path("reference")
                        .path("criterion_reference")
                        .asString();
        return List.of(
                new Case("A", a, "SUPPORTED", "NONE", eventReference),
                new Case(
                        "B",
                        b,
                        "UNSUPPORTED",
                        "OBSERVATION_NOT_GROUNDED",
                        emotion.path("reference").asString()));
    }

    Report run(LlmProvider provider) {
        return run(provider, RoleplayTurnDeadline.start(), ignored -> {});
    }

    Report run(LlmProvider provider, RoleplayTurnDeadline deadline, Consumer<Entry> completed) {
        var entries = new ArrayList<Entry>();
        String stopped = null;
        for (var sample : cases()) {
            var result = new AnalysisReviewDiagnostic(sample.source()).run(provider, deadline);
            var entry = new Entry(sample.id(), result, score(sample, result));
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
                entries.size() == 2 && entries.stream().allMatch(e -> e.score().verdictMatches()),
                stopped);
    }

    private static Score score(Case sample, AnalysisReviewDiagnostic.Result result) {
        if (result.review() == null) {
            return new Score(
                    sample.expectedStatus(),
                    sample.expectedReason(),
                    null,
                    null,
                    false,
                    false,
                    true);
        }
        var review = result.review().path("observation_review");
        String status = review.path("status").asString();
        String reason = review.path("reason_code").asString();
        boolean referenced = false;
        for (var ref : review.path("criterion_references")) {
            referenced |= ref.asString().equals(sample.expectedReference());
        }
        return new Score(
                sample.expectedStatus(),
                sample.expectedReason(),
                status,
                reason,
                status.equals(sample.expectedStatus()) && reason.equals(sample.expectedReason()),
                referenced,
                review.path("criterion_references").isEmpty());
    }

    record Case(
            String id,
            JsonNode source,
            String expectedStatus,
            String expectedReason,
            String expectedReference) {}

    record Score(
            String expectedStatus,
            String expectedReason,
            String actualStatus,
            String actualReason,
            boolean verdictMatches,
            boolean expectedReferenceCited,
            boolean emptyReferences) {}

    record Entry(String caseId, AnalysisReviewDiagnostic.Result result, Score score) {}

    record Report(
            List<Entry> cases,
            int physicalCalls,
            boolean discriminationSuccess,
            String stoppedReason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("승인된 고정 대조쌍만 실행합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        var diagnostic = new ObservationPairDiagnostic();
        Path dir = RoleplayEvalMain.diagnosticOutput("observation-pair", model);
        for (var sample : diagnostic.cases()) {
            Path caseDir = dir.resolve(sample.id());
            Files.createDirectory(caseDir);
            var request =
                    new AnalysisReviewDiagnostic(sample.source())
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
                                "baseline_run",
                                diagnostic.frozen.path("baseline_run").asString(),
                                "baseline_request_sha256",
                                diagnostic.frozen.path("baseline_request_sha256").asString(),
                                "requested_model",
                                model,
                                "prompt_version",
                                AnalysisReviewDiagnostic.PROMPT_VERSION,
                                "schema_version",
                                AnalysisReviewDiagnostic.SCHEMA_VERSION,
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
                                    // 다음 호출 전에 완료한 결과를 남긴다.
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
                    "관찰 대조쌍: calls=%d, discrimination=%s, stopped=%s%n결과: %s%n",
                    report.physicalCalls(),
                    report.discriminationSuccess(),
                    report.stoppedReason(),
                    dir.toAbsolutePath());
        }
    }
}
