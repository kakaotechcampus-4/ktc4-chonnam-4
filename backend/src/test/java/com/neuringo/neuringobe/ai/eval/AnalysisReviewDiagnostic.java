package com.neuringo.neuringobe.ai.eval;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 고정된 합성 분석만 한 번 검수한다. 운영 판정과 재시도에는 연결하지 않는다. */
public final class AnalysisReviewDiagnostic {
    static final String FIXTURE = "eval/analysis-review-p1-v1.json";
    static final String PROMPT_VERSION = "roleplay-analysis-review-diagnostic/v1";
    static final String SCHEMA_VERSION = "analysis-review-diagnostic/v1";
    static final Map<String, List<String>> FAILURE_REASONS =
            Map.of(
                    "observation_review", List.of("OBSERVATION_NOT_GROUNDED"),
                    "activity_review", List.of("ACTIVITY_ASSESSMENT_NOT_GROUNDED"),
                    "strategy_review",
                            List.of("STATE_STRATEGY_MISMATCH", "UNSUPPORTED_SUPPORT_LEVEL"));
    static final Map<String, List<String>> MISSING_REASONS =
            Map.of(
                    "observation_review",
                            List.of("MISSING_OBSERVATION_CONTEXT", "MISSING_SUPPORT_HISTORY"),
                    "activity_review",
                            List.of("MISSING_ACTIVITY_CONTEXT", "MISSING_SUPPORT_HISTORY"),
                    "strategy_review",
                            List.of("MISSING_STRATEGY_CONTEXT", "MISSING_SUPPORT_HISTORY"));
    static final List<String> REVIEWS =
            List.of("observation_review", "activity_review", "strategy_review");

    private final JsonNode source;
    private final ObjectNode input;
    private final String prompt = resource("eval/analysis-review-prompt-v1.md");
    private final String schema;
    private final Schema validator;

    AnalysisReviewDiagnostic() {
        this(EvalReport.JSON.readTree(resource(FIXTURE)));
    }

    AnalysisReviewDiagnostic(JsonNode source) {
        this.source = source.deepCopy();
        input = ((ObjectNode) this.source.path("input")).deepCopy();
        input.remove("candidate_response");
        schema = createSchema();
        validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(EvalReport.JSON.readTree(schema));
    }

    LlmRequest request(RoleplayTurnDeadline deadline) {
        UUID activity =
                UUID.nameUUIDFromBytes("analysis-review-p1".getBytes(StandardCharsets.UTF_8));
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        activity,
                        null,
                        null,
                        null,
                        null,
                        activity,
                        UUID.fromString(input.path("learner_turn").path("turn_id").asString()),
                        UUID.fromString(source.path("analysis_id").asString()),
                        UUID.fromString(source.path("candidate_id").asString()));
        return new LlmRequest(
                        trace,
                        new AiAttemptContext(0, 0, 1),
                        AiOperation.RESPONSE_EVALUATION,
                        1,
                        prompt,
                        input.toString(),
                        PROMPT_VERSION,
                        SCHEMA_VERSION,
                        "diagnostic-only",
                        deadline.callBudget())
                .withResponseJsonSchema(schema);
    }

    Result run(LlmProvider provider) {
        return run(provider, RoleplayTurnDeadline.start(Duration.ofSeconds(60)));
    }

    Result run(LlmProvider provider, RoleplayTurnDeadline deadline) {
        return execute(provider, deadline, request(deadline), this::validate);
    }

    static Result execute(
            LlmProvider provider,
            RoleplayTurnDeadline deadline,
            LlmRequest request,
            Function<String, JsonNode> validate) {
        long started = System.nanoTime();
        var calls = new AtomicInteger();
        AiCallResult<LlmCompletion> result;
        try {
            // 이 진단에는 오케스트레이터와 검증 재시도 루프를 사용하지 않는다.
            result =
                    deadline.withinBudget(
                            () -> {
                                calls.incrementAndGet();
                                return provider.complete(request);
                            });
        } catch (RoleplayTurnDeadline.Expired failure) {
            return new Result(
                    "TIMED_OUT",
                    calls.get(),
                    elapsed(started),
                    null,
                    null,
                    null,
                    "deadline exceeded");
        } catch (RuntimeException failure) {
            return new Result(
                    "EXECUTION_FAILURE",
                    calls.get(),
                    elapsed(started),
                    null,
                    null,
                    null,
                    "provider execution failed");
        }
        if (result instanceof AiCallResult.Failure<LlmCompletion> failure) {
            return new Result(
                    "PROVIDER_FAILURE",
                    calls.get(),
                    elapsed(started),
                    failure.metadata(),
                    null,
                    null,
                    failure.failure().type().name());
        }
        var success = (AiCallResult.Success<LlmCompletion>) result;
        String raw = success.data().content();
        try {
            JsonNode review = validate.apply(raw);
            return new Result(
                    "VALID_DIAGNOSTIC_OUTPUT",
                    calls.get(),
                    elapsed(started),
                    success.metadata(),
                    raw,
                    review,
                    null);
        } catch (IllegalArgumentException failure) {
            return new Result(
                    "INVALID_DIAGNOSTIC_OUTPUT",
                    calls.get(),
                    elapsed(started),
                    success.metadata(),
                    raw,
                    null,
                    failure.getMessage());
        }
    }

    JsonNode validate(String raw) {
        JsonNode output;
        try {
            output = EvalReport.JSON.readTree(raw);
            if (output == null || !validator.validate(output).isEmpty()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("diagnostic output does not match schema");
        }
        for (String field : REVIEWS) {
            var review = output.path(field);
            String reason = review.path("reason_code").asString();
            boolean consistent =
                    switch (review.path("status").asString()) {
                        case "SUPPORTED" -> reason.equals("NONE");
                        case "UNSUPPORTED" -> FAILURE_REASONS.get(field).contains(reason);
                        case "INSUFFICIENT_CONTEXT" -> MISSING_REASONS.get(field).contains(reason);
                        default -> false;
                    };
            if (!consistent)
                throw new IllegalArgumentException("diagnostic status and reason conflict");
            Set<String> seen = new HashSet<>();
            for (var reference : review.path("criterion_references")) {
                if (!seen.add(reference.asString())) {
                    throw new IllegalArgumentException(
                            "diagnostic criterion references are duplicated");
                }
            }
        }
        return output;
    }

    private String createSchema() {
        var references = new java.util.ArrayList<String>();
        for (var criterion : input.path("goal_observation_context").path("approved_criteria")) {
            references.add(criterion.path("reference").asString());
        }
        var properties = new LinkedHashMap<String, Object>();
        properties.put(
                "analysis_id",
                Map.of("type", "string", "enum", List.of(source.path("analysis_id").asString())));
        for (String field : REVIEWS) {
            var reasons = new java.util.ArrayList<>(List.of("NONE"));
            reasons.addAll(FAILURE_REASONS.get(field));
            reasons.addAll(MISSING_REASONS.get(field));
            var review = new LinkedHashMap<String, Object>();
            review.put(
                    "status",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("SUPPORTED", "UNSUPPORTED", "INSUFFICIENT_CONTEXT")));
            review.put("reason_code", Map.of("type", "string", "enum", reasons));
            review.put(
                    "criterion_references",
                    Map.of("type", "array", "items", Map.of("type", "string", "enum", references)));
            properties.put(field, object(review));
        }
        return EvalReport.JSON.writeValueAsString(object(properties));
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                List.copyOf(properties.keySet()),
                "additionalProperties",
                false);
    }

    private static String resource(String name) {
        try (var stream = AnalysisReviewDiagnostic.class.getResourceAsStream("/" + name)) {
            return new String(
                    Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    record Result(
            String outcome,
            int physicalCalls,
            long totalMs,
            AiCallMetadata metadata,
            String rawOutput,
            JsonNode review,
            String error) {}

    public static void main(String[] args) throws IOException {
        boolean serviceContext = args.length == 1 && args[0].equals("--service-context");
        if (args.length != 0 && !serviceContext)
            throw new IllegalArgumentException("진단은 승인된 합성 P1 한 건만 실행합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        var diagnostic =
                serviceContext
                        ? new AnalysisReviewDiagnostic(serviceFixture())
                        : new AnalysisReviewDiagnostic();
        Path dir =
                RoleplayEvalMain.diagnosticOutput(
                        serviceContext ? "service-analysis-review" : "analysis-review", model);
        Files.writeString(dir.resolve("source-fixture.json"), diagnostic.source.toString());
        Files.writeString(dir.resolve("request.json"), diagnostic.input.toString());
        Files.writeString(dir.resolve("schema.json"), diagnostic.schema);
        Files.writeString(dir.resolve("system-prompt.md"), diagnostic.prompt);
        Files.writeString(
                dir.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "source_run",
                                diagnostic.source.path("source_run").asString(),
                                "source_sequence",
                                diagnostic.source.path("source_sequence").asInt(),
                                "requested_model",
                                model,
                                "prompt_version",
                                PROMPT_VERSION,
                                "schema_version",
                                SCHEMA_VERSION,
                                "budget_ms",
                                60000,
                                "sdk_max_retries",
                                0)));
        try (var context = RoleplayEvalMain.openAiContext(model)) {
            Result result = diagnostic.run(context.getBean(LlmProvider.class));
            Files.writeString(
                    dir.resolve("result.json"), EvalReport.JSON.writeValueAsString(result));
            System.out.printf(
                    "분석 전용 진단: %s, %dms, calls=%d%n결과: %s%n",
                    result.outcome(),
                    result.totalMs(),
                    result.physicalCalls(),
                    dir.toAbsolutePath());
        }
    }

    private static JsonNode serviceFixture() throws IOException {
        var source =
                EvalReport.JSON.readTree(
                        Files.readString(
                                Path.of("build/eval/service-context-p1-fixture/source.json")));
        if (!source.path("source_run").asString().equals("service-context-synthetic-p1-v1")
                || !source.path("assembly_evidence").path("synthetic_only").asBoolean()
                || !source.path("assembly_evidence").path("real_progress_policy").asBoolean()) {
            throw new IllegalArgumentException("서비스 조립 경로로 검증한 합성 P1 입력이 필요합니다.");
        }
        return source;
    }
}
