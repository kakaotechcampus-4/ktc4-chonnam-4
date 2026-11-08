package com.neuringo.neuringobe.ai.eval;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.PreparedPrompt;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 근거 키 선택을 원본에 연결하고 기존 통합 평가 검증을 그대로 적용한다. */
public final class EvidenceSelectionDiagnostic {
    static final String PROMPT_VERSION = "roleplay-evidence-selection-diagnostic/v1";
    static final String SCHEMA_VERSION = "evidence-selection-diagnostic/v1";
    private final List<SelectionCase> cases;

    EvidenceSelectionDiagnostic() {
        String prompt;
        try (var stream = getClass().getResourceAsStream("/eval/evidence-selection-prompt-v1.md")) {
            prompt =
                    new String(
                            java.util.Objects.requireNonNull(stream).readAllBytes(),
                            StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        cases =
                new IntegratedEvaluationDiagnostic()
                        .samples().stream()
                                .map(sample -> new SelectionCase(sample, prompt))
                                .toList();
    }

    List<SelectionCase> cases() {
        return cases;
    }

    IntegratedEvaluationDiagnostic.Report run(
            LlmProvider provider,
            RoleplayTurnDeadline deadline,
            Consumer<IntegratedEvaluationDiagnostic.Entry> completed) {
        return new IntegratedEvaluationDiagnostic(
                        cases.stream().map(SelectionCase::sample).toList())
                .run(provider, deadline, completed);
    }

    static final class SelectionCase {
        private final IntegratedEvaluationDiagnostic.Sample baseline;
        private final IntegratedEvaluationDiagnostic.Sample sample;
        private final LinkedHashMap<String, JsonNode> references = new LinkedHashMap<>();
        private final ObjectNode catalog;
        private final Schema validator;
        private final String utterance;
        private String resolvedOutput;

        SelectionCase(IntegratedEvaluationDiagnostic.Sample baseline, String prompt) {
            this.baseline = baseline;
            var original = baseline.prepared().request();
            var input = (ObjectNode) EvalReport.JSON.readTree(original.userPrompt());
            utterance = input.path("learner_turn").path("canonical_utterance").asString();
            catalog = EvalReport.JSON.createObjectNode();
            catalog.putObject("sources")
                    .putObject("u0")
                    .put("kind", "CURRENT_UTTERANCE")
                    .set("turn_id", input.path("learner_turn").path("turn_id"));
            var activity = catalog.putObject("activity_references");
            for (var prior : input.path("goal_observation_context").path("confirmed_observations"))
                addReference(activity, prior, "CONFIRMED");
            for (var task : input.path("observation_tasks"))
                addReference(activity, task, "CURRENT");
            input.set("evidence_catalog", catalog);
            var schema = (ObjectNode) EvalReport.JSON.readTree(original.responseJsonSchema());
            var properties = schema.path("properties");
            for (String key : input.path("observation_tasks").propertyNames()) {
                ((ObjectNode) properties.path("observation_reviews").path("properties"))
                        .set(
                                key,
                                observationSchema(
                                        input.path("observation_tasks")
                                                .path(key)
                                                .path("status")
                                                .asString()));
            }
            var activitySchema = (ObjectNode) properties.path("activity_review");
            var activityProperties = (ObjectNode) activitySchema.path("properties");
            activityProperties.remove("evidence_references");
            var selection = object();
            references
                    .keySet()
                    .forEach(
                            key ->
                                    ((ObjectNode) selection.path("properties"))
                                            .set(
                                                    key,
                                                    EvalReport.JSON
                                                            .createObjectNode()
                                                            .put("type", "boolean")));
            required(selection);
            activityProperties.set("evidence_selection", selection);
            required(activitySchema);
            validator =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(schema);
            var request =
                    new LlmRequest(
                                    original.traceContext(),
                                    original.attemptContext(),
                                    original.operation(),
                                    1,
                                    prompt,
                                    input.toString(),
                                    PROMPT_VERSION,
                                    SCHEMA_VERSION,
                                    original.policyVersion())
                            .withResponseJsonSchema(schema.toString());
            sample =
                    new IntegratedEvaluationDiagnostic.Sample(
                            baseline.id(),
                            baseline.source(),
                            new PreparedPrompt<>(
                                    request,
                                    raw -> {
                                        String resolved = resolve(raw);
                                        resolvedOutput = resolved;
                                        return baseline.prepared().parser().parse(resolved);
                                    }));
        }

        private void addReference(ObjectNode catalog, JsonNode observation, String scope) {
            var reference = observation.path("reference");
            if (references.values().stream().anyMatch(reference::equals)) return;
            String key = "r" + references.size();
            references.put(key, reference.deepCopy());
            var entry = catalog.putObject(key).put("source_scope", scope);
            entry.set("reference", reference);
            entry.set("status", observation.path("status"));
        }

        IntegratedEvaluationDiagnostic.Sample sample() {
            return sample;
        }

        JsonNode catalog() {
            return catalog.deepCopy();
        }

        String resolvedOutput() {
            return resolvedOutput;
        }

        String resolve(String raw) {
            ObjectNode output;
            try {
                var node = EvalReport.JSON.readTree(raw);
                if (node == null || !validator.validate(node).isEmpty())
                    throw new IllegalArgumentException();
                output = (ObjectNode) node.deepCopy();
            } catch (RuntimeException failure) {
                throw new InvalidLlmOutputException(
                        "evidence selection does not match schema", null);
            }
            if (output.path("observation_reviews").isObject()) {
                for (var value : output.path("observation_reviews")) {
                    var row = (ObjectNode) value;
                    String source = row.path("evidence_source").asString();
                    row.remove("evidence_source");
                    if (source.equals("u0")) row.put("evidence_quote", utterance);
                    else if (source.equals("NONE")) row.putNull("evidence_quote");
                    else throw new InvalidLlmOutputException("unknown evidence source", null);
                }
            }
            if (output.path("activity_review").isObject()) {
                var activity = (ObjectNode) output.path("activity_review");
                var selected = activity.remove("evidence_selection");
                var refs = activity.putArray("evidence_references");
                for (String key : references.keySet())
                    if (selected.path(key).asBoolean()) refs.add(references.get(key).deepCopy());
            }
            return output.toString();
        }
    }

    static ObjectNode observationSchema(String status) {
        var choices = EvalReport.JSON.createObjectNode().putArray("anyOf");
        if (status.equals("NOT_EXPRESSED"))
            choices.add(choice("SUPPORTED", "ABSENCE_CONFIRMED", "NONE"));
        else {
            choices.add(choice("SUPPORTED", "MATCHED_EXPRESSION", "u0"));
            choices.add(choice("UNSUPPORTED", "NO_MATCHING_EXPRESSION", "NONE"));
        }
        choices.add(choice("UNSUPPORTED", "STATUS_MISMATCH", "u0"));
        choices.add(choice("INSUFFICIENT_CONTEXT", "INSUFFICIENT_CONTEXT", "NONE"));
        var result = EvalReport.JSON.createObjectNode();
        result.set("anyOf", choices);
        return result;
    }

    private static ObjectNode choice(String verdict, String reason, String source) {
        var result = object();
        var properties = (ObjectNode) result.path("properties");
        properties.set("verdict", singleton(verdict));
        properties.set("reason_code", singleton(reason));
        properties.set("evidence_source", singleton(source));
        properties.set("explanation", EvalReport.JSON.createObjectNode().put("type", "string"));
        required(result);
        return result;
    }

    private static ObjectNode singleton(String value) {
        var result = EvalReport.JSON.createObjectNode().put("type", "string");
        result.putArray("enum").add(value);
        return result;
    }

    private static ObjectNode object() {
        var result =
                EvalReport.JSON
                        .createObjectNode()
                        .put("type", "object")
                        .put("additionalProperties", false);
        result.putObject("properties");
        return result;
    }

    private static void required(ObjectNode node) {
        var required = node.putArray("required");
        node.path("properties").propertyNames().forEach(required::add);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("승인된 고정 A/B만 허용합니다.");
        String model = RoleplayEvalMain.requireEnv("AI_MODEL");
        Path output = RoleplayEvalMain.diagnosticOutput("evidence-selection", model);
        var diagnostic = new EvidenceSelectionDiagnostic();
        for (var c : diagnostic.cases()) {
            var sample = c.sample();
            var request = sample.prepared().request();
            var dir = output.resolve(sample.id());
            Files.createDirectory(dir);
            Files.writeString(dir.resolve("source-fixture.json"), sample.source().toString());
            Files.writeString(dir.resolve("catalog.json"), c.catalog().toString());
            Files.writeString(dir.resolve("request.json"), request.userPrompt());
            Files.writeString(dir.resolve("schema.json"), request.responseJsonSchema());
            Files.writeString(dir.resolve("system-prompt.md"), request.systemPrompt());
            Files.writeString(
                    dir.resolve("source.json"),
                    EvalReport.JSON.writeValueAsString(
                            Map.of(
                                    "request_sha256",
                                    IntegratedEvaluationDiagnostic.hash(request.userPrompt()),
                                    "schema_sha256",
                                    IntegratedEvaluationDiagnostic.hash(
                                            request.responseJsonSchema()),
                                    "prompt_sha256",
                                    IntegratedEvaluationDiagnostic.hash(request.systemPrompt()))));
        }
        Files.writeString(
                output.resolve("source.json"),
                EvalReport.JSON.writeValueAsString(
                        Map.of(
                                "requested_model",
                                model,
                                "prompt_version",
                                PROMPT_VERSION,
                                "schema_version",
                                SCHEMA_VERSION,
                                "maximum_calls",
                                2,
                                "shared_budget_ms",
                                60000,
                                "sdk_max_retries",
                                0,
                                "baseline_run",
                                "elice-p1-scoped-integrated-01",
                                "quote_semantics",
                                "EXPLICIT_REFERENCE_TO_FULL_CURRENT_UTTERANCE",
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
                                    var dir = output.resolve(entry.caseId());
                                    Files.writeString(
                                            dir.resolve("result.json"),
                                            EvalReport.JSON.writeValueAsString(entry));
                                    var c =
                                            diagnostic.cases().stream()
                                                    .filter(
                                                            v ->
                                                                    v.sample()
                                                                            .id()
                                                                            .equals(entry.caseId()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    if (c.resolvedOutput() != null)
                                        Files.writeString(
                                                dir.resolve("resolved-output.json"),
                                                c.resolvedOutput());
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            });
            Files.writeString(
                    output.resolve("result.json"), EvalReport.JSON.writeValueAsString(report));
            System.out.printf(
                    "근거 선택 진단: calls=%d, server_accepted=%s, stopped=%s%n결과: %s%n",
                    report.physicalCalls(),
                    report.bothServerAccepted(),
                    report.stoppedReason(),
                    output.toAbsolutePath());
        }
    }
}
