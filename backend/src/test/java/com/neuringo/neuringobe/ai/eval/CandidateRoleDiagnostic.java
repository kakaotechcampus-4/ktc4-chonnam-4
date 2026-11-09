package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.CandidateRoleValidation;
import com.neuringo.neuringobe.ai.application.prompt.SplitReviewValidation;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 기존 실제 승인 분석을 검증해 재사용하고 고정 두 후보의 역할·코드 정합성을 진단한다. */
public final class CandidateRoleDiagnostic {
    static final String VERSION = "roleplay-candidate-role-diagnostic/v1";
    static final String SCHEMA_VERSION = "candidate-role-diagnostic/v1";
    static final String BASELINE = "candidate-role-baseline-v1.json";
    private static final Set<String> STOP =
            Set.of("PROVIDER_FAILURE", "SCHEMA_FAILURE", "TIMED_OUT", "EXECUTION_FAILURE");
    private final JsonNode baseline;
    private final List<Sample> samples;

    CandidateRoleDiagnostic() {
        this(loadBaseline());
    }

    CandidateRoleDiagnostic(JsonNode baseline) {
        this.baseline = baseline.deepCopy();
        var original = verifiedSource(this.baseline);
        var validation = new SplitReviewValidation(original.path("input"));
        String review =
                EvalReport.JSON
                        .readTree(baseline.path("analysis_result_json").asString())
                        .path("raw_output")
                        .asString();
        var a = original.path("input").path("candidate_response").deepCopy();
        var c = (ObjectNode) a.deepCopy();
        c.put(
                "candidate_id",
                UUID.nameUUIDFromBytes("candidate-role-negative-C".getBytes(StandardCharsets.UTF_8))
                        .toString());
        c.put("text", "친구가 발표하다가 말을 찾지 못해서 자리에 앉았어.");
        samples =
                List.of(
                        prepare("A", validation.candidateReview(review, a), "IN_CHARACTER"),
                        prepare(
                                "C",
                                validation.candidateReview(review, c),
                                "SELF_AS_THIRD_PERSON_NARRATION"));
    }

    static JsonNode loadBaseline() {
        return EvalReport.JSON.readTree(resource(BASELINE));
    }

    List<Sample> samples() {
        return samples;
    }

    private static JsonNode verifiedSource(JsonNode baseline) {
        for (String key :
                List.of(
                        "source_fixture_json",
                        "analysis_request_json",
                        "analysis_schema_json",
                        "analysis_system_prompt",
                        "analysis_result_json",
                        "candidate_result_json"))
            require(
                    IntegratedEvaluationDiagnostic.hash(baseline.path(key).asString())
                            .equals(baseline.path("file_sha256").path(key).asString()));
        var source = EvalReport.JSON.readTree(baseline.path("source_fixture_json").asString());
        require(source.equals(new ObservationPairDiagnostic().cases().getFirst().source()));
        var validation = new SplitReviewValidation(source.path("input"));
        require(
                validation
                        .analysisInput()
                        .equals(
                                EvalReport.JSON.readTree(
                                        baseline.path("analysis_request_json").asString())));
        require(
                normalizedSchema(EvalReport.JSON.readTree(validation.analysisSchema()))
                        .equals(
                                normalizedSchema(
                                        EvalReport.JSON.readTree(
                                                baseline.path("analysis_schema_json")
                                                        .asString()))));
        require(
                resource("split-analysis-prompt-v1.md")
                        .equals(baseline.path("analysis_system_prompt").asString()));
        var manifest = baseline.path("analysis_manifest");
        for (var pair :
                Map.of(
                                "request_sha256",
                                "analysis_request_json",
                                "schema_sha256",
                                "analysis_schema_json",
                                "prompt_sha256",
                                "analysis_system_prompt")
                        .entrySet())
            require(
                    IntegratedEvaluationDiagnostic.hash(baseline.path(pair.getValue()).asString())
                            .equals(manifest.path(pair.getKey()).asString()));
        require(
                manifest.path("prompt_version")
                        .asString()
                        .equals(SplitReviewDiagnostic.ANALYSIS_VERSION));
        require(manifest.path("schema_version").asString().equals("split-analysis-diagnostic/v1"));
        var result = EvalReport.JSON.readTree(baseline.path("analysis_result_json").asString());
        require(
                result.path("outcome").asString().equals("VALID_REVIEW")
                        && result.path("physical_calls").asInt() == 1);
        require(
                result.path("metadata")
                        .path("prompt_version")
                        .equals(manifest.path("prompt_version")));
        require(
                result.path("metadata")
                        .path("response_schema_version")
                        .equals(manifest.path("schema_version")));
        // 승인 여부는 저장된 집계 플래그가 아니라 원본 응답을 기존 검증기에 다시 넣어 확인한다.
        validation.candidateReview(
                result.path("raw_output").asString(),
                source.path("input").path("candidate_response"));
        return source;
    }

    // 스키마의 열거·필수 필드 나열 순서는 JVM마다 달라질 수 있으므로 제약 내용만 비교한다.
    static JsonNode normalizedSchema(JsonNode node) {
        if (node.isObject()) {
            var copy = (ObjectNode) node.deepCopy();
            for (String key : node.propertyNames()) {
                var child = normalizedSchema(node.path(key));
                if (Set.of("enum", "required").contains(key) && child.isArray()) {
                    var values = new ArrayList<JsonNode>();
                    child.forEach(values::add);
                    values.sort(java.util.Comparator.comparing(JsonNode::toString));
                    var sorted = EvalReport.JSON.createArrayNode();
                    values.forEach(sorted::add);
                    child = sorted;
                }
                copy.set(key, child);
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = EvalReport.JSON.createArrayNode();
            node.forEach(value -> copy.add(normalizedSchema(value)));
            return copy;
        }
        return node.deepCopy();
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("기존 승인 분석의 입력·스키마·프롬프트·해시가 일치하지 않습니다.");
    }

    private Sample prepare(String id, SplitReviewValidation.CandidateReview base, String expected) {
        var validation = new CandidateRoleValidation(base);
        var input = validation.input();
        var session = UUID.nameUUIDFromBytes("candidate-role-p1".getBytes(StandardCharsets.UTF_8));
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        session,
                        null,
                        null,
                        null,
                        null,
                        session,
                        UUID.fromString(input.path("learner_turn").path("turn_id").asString()),
                        UUID.fromString(input.path("analysis_id").asString()),
                        UUID.fromString(
                                input.path("candidate_response").path("candidate_id").asString()));
        var request =
                new LlmRequest(
                                trace,
                                new AiAttemptContext(0, 0, 1),
                                AiOperation.RESPONSE_EVALUATION,
                                1,
                                resource("candidate-role-prompt-v1.md"),
                                input.toString(),
                                VERSION,
                                SCHEMA_VERSION,
                                null)
                        .withResponseJsonSchema(validation.schema());
        return new Sample(id, validation, request, expected);
    }

    Report run(LlmProvider provider, RoleplayTurnDeadline deadline, Consumer<Entry> completed) {
        var entries = new ArrayList<Entry>();
        String stopped = null;
        for (var sample : samples) {
            var entry = call(sample, provider, deadline);
            entries.add(entry);
            completed.accept(entry);
            if (STOP.contains(entry.outcome())) {
                stopped = entry.outcome();
                break;
            }
        }
        return new Report(
                List.copyOf(entries),
                entries.stream().mapToInt(Entry::physicalCalls).sum(),
                entries.size() == 2
                        && entries.stream()
                                .allMatch(
                                        e ->
                                                e.checks() != null
                                                        && e.checks()
                                                                .outcome()
                                                                .equals("VALID_CANDIDATE_REVIEW")),
                entries.size() == 2 && entries.stream().allMatch(Entry::roleMatchesExpected),
                stopped);
    }

    private Entry call(Sample sample, LlmProvider provider, RoleplayTurnDeadline deadline) {
        long start = System.nanoTime();
        var calls = new AtomicInteger();
        var raw = new AtomicReference<String>();
        var metadata = new AtomicReference<AiCallMetadata>();
        CandidateRoleValidation.Checks checks = null;
        String outcome;
        String reason = null;
        try {
            var response =
                    deadline.withinBudget(
                            () -> {
                                var budget = deadline.callBudget();
                                calls.incrementAndGet();
                                var result =
                                        provider.complete(sample.request().withCallBudget(budget));
                                if (result instanceof AiCallResult.Success<LlmCompletion> success) {
                                    metadata.set(success.metadata());
                                    raw.set(success.data().content());
                                } else if (result
                                        instanceof AiCallResult.Failure<LlmCompletion> failure)
                                    metadata.set(failure.metadata());
                                return result;
                            });
            if (response instanceof AiCallResult.Failure<LlmCompletion> failure) {
                outcome = "PROVIDER_FAILURE";
                reason = failure.failure().type().name();
            } else {
                checks = sample.validation().validate(raw.get());
                deadline.requireActive();
                outcome = checks.outcome();
                reason = checks.reason();
            }
        } catch (RoleplayTurnDeadline.Expired failure) {
            checks = null;
            outcome = "TIMED_OUT";
            reason = "shared deadline exceeded";
        } catch (RuntimeException failure) {
            checks = null;
            outcome = "EXECUTION_FAILURE";
            reason = "diagnostic execution failed";
        }
        return new Entry(
                sample.id(),
                outcome,
                calls.get(),
                (System.nanoTime() - start) / 1_000_000,
                metadata.get(),
                raw.get(),
                checks,
                sample.expectedRole(),
                checks != null && sample.expectedRole().equals(checks.roleVerdict()),
                reason);
    }

    private static String resource(String name) {
        try (var stream = CandidateRoleDiagnostic.class.getResourceAsStream("/eval/" + name)) {
            return new String(
                    java.util.Objects.requireNonNull(stream).readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    record Sample(
            String id,
            CandidateRoleValidation validation,
            LlmRequest request,
            String expectedRole) {}

    record Entry(
            String caseId,
            String outcome,
            int physicalCalls,
            long totalMs,
            AiCallMetadata metadata,
            String rawOutput,
            CandidateRoleValidation.Checks checks,
            String expectedRole,
            boolean roleMatchesExpected,
            String reason) {}

    record Report(
            List<Entry> cases,
            int physicalCalls,
            boolean bothServerAccepted,
            boolean bothRoleLabelsMatch,
            String stoppedReason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("고정 두 후보만 허용합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        Path output = RoleplayEvalMain.diagnosticOutput("candidate-role", model);
        var diagnostic = new CandidateRoleDiagnostic();
        Files.writeString(output.resolve("baseline.json"), diagnostic.baseline.toString());
        for (var sample : diagnostic.samples) {
            var dir = output.resolve(sample.id());
            Files.createDirectory(dir);
            var request = sample.request();
            Files.writeString(dir.resolve("request.json"), request.userPrompt());
            Files.writeString(
                    dir.resolve("speaker-context.json"),
                    sample.validation().input().path("speaker_context").toString());
            Files.writeString(dir.resolve("schema.json"), request.responseJsonSchema());
            Files.writeString(dir.resolve("system-prompt.md"), request.systemPrompt());
            Files.writeString(
                    dir.resolve("source.json"),
                    EvalReport.JSON.writeValueAsString(
                            Map.of(
                                    "request_sha256",
                                    IntegratedEvaluationDiagnostic.hash(request.userPrompt()),
                                    "prompt_sha256",
                                    IntegratedEvaluationDiagnostic.hash(request.systemPrompt()),
                                    "schema_sha256",
                                    IntegratedEvaluationDiagnostic.hash(
                                            request.responseJsonSchema()),
                                    "prompt_version",
                                    VERSION,
                                    "schema_version",
                                    SCHEMA_VERSION)));
        }
        Files.writeString(
                output.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "requested_model",
                                model,
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
                                "baseline_resource",
                                BASELINE,
                                "baseline_sha256",
                                IntegratedEvaluationDiagnostic.hash(resource(BASELINE)),
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
                    "후보 역할 진단: calls=%d, server_accepted=%s, role_labels_match=%s, stopped=%s%n결과: %s%n",
                    report.physicalCalls(),
                    report.bothServerAccepted(),
                    report.bothRoleLabelsMatch(),
                    report.stoppedReason(),
                    output.toAbsolutePath());
        }
    }
}
