package com.neuringo.neuringobe.ai.eval;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 관찰별 판정과 실제 발화 인용을 대조한다. 의미의 타당성까지 자동 승인하지 않는다. */
final class ObservationEvidenceReview {
    static final String PROMPT_VERSION = "roleplay-observation-evidence-diagnostic/v1";
    static final String SCHEMA_VERSION = "observation-evidence-diagnostic/v1";
    private final AnalysisReviewDiagnostic base;
    private final Set<String> expected = new HashSet<>();
    private final String utterance;
    private final String prompt;
    private final String schema;
    private final Schema validator;

    ObservationEvidenceReview(JsonNode source) {
        base = new AnalysisReviewDiagnostic(source);
        var input = source.path("input");
        utterance = input.path("learner_turn").path("canonical_utterance").asString();
        for (var observation :
                input.path("analysis_result").path("goal_observation").path("observations")) {
            expected.add(observation.path("reference").path("criterion_reference").asString());
        }
        try (var stream =
                getClass().getResourceAsStream("/eval/observation-evidence-prompt-v1.md")) {
            prompt =
                    new String(
                            Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        schema = schema(source);
        validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(EvalReport.JSON.readTree(schema));
    }

    LlmRequest request(RoleplayTurnDeadline deadline) {
        var original = base.request(deadline);
        return new LlmRequest(
                original.traceContext(),
                original.attemptContext(),
                original.operation(),
                1,
                prompt,
                original.userPrompt(),
                PROMPT_VERSION,
                SCHEMA_VERSION,
                "diagnostic-only",
                deadline.callBudget(),
                schema);
    }

    AnalysisReviewDiagnostic.Result run(LlmProvider provider, RoleplayTurnDeadline deadline) {
        return AnalysisReviewDiagnostic.execute(
                provider, deadline, request(deadline), this::validate);
    }

    JsonNode validate(String raw) {
        JsonNode output;
        try {
            output = EvalReport.JSON.readTree(raw);
            if (output == null || !validator.validate(output).isEmpty())
                throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("observation evidence output does not match schema");
        }
        var seen = new HashSet<String>();
        for (var review : output.path("reviews")) {
            String ref = review.path("criterion_reference").asString();
            if (!expected.contains(ref) || !seen.add(ref)) {
                throw new IllegalArgumentException("review does not match current observations");
            }
            String explanation = review.path("explanation").asString();
            if (explanation.isBlank()
                    || explanation.codePointCount(0, explanation.length()) > 200) {
                throw new IllegalArgumentException(
                        "observation explanation must be within 200 characters");
            }
            String quote =
                    review.path("evidence_quote").isNull()
                            ? null
                            : review.path("evidence_quote").asString();
            if (quote != null && (quote.isBlank() || !utterance.contains(quote))) {
                throw new IllegalArgumentException(
                        "evidence quote is not in the learner utterance");
            }
            String reason = review.path("reason_code").asString();
            boolean consistent =
                    switch (review.path("verdict").asString()) {
                        case "SUPPORTED" -> reason.equals("MATCHED_EXPRESSION") && quote != null;
                        case "UNSUPPORTED" ->
                                reason.equals("NO_MATCHING_EXPRESSION")
                                        || (reason.equals("STATUS_MISMATCH") && quote != null);
                        case "INSUFFICIENT_CONTEXT" -> reason.equals("INSUFFICIENT_CONTEXT");
                        default -> false;
                    };
            if (!consistent)
                throw new IllegalArgumentException("observation verdict and evidence conflict");
        }
        if (!seen.equals(expected))
            throw new IllegalArgumentException("current observation reviews are missing");
        return output;
    }

    private static String schema(JsonNode source) {
        var references = new java.util.ArrayList<String>();
        for (var criterion :
                source.path("input").path("goal_observation_context").path("approved_criteria")) {
            references.add(criterion.path("reference").asString());
        }
        var review = new LinkedHashMap<String, Object>();
        review.put("criterion_reference", Map.of("type", "string", "enum", references));
        review.put("evidence_quote", Map.of("type", List.of("string", "null")));
        review.put(
                "verdict",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("SUPPORTED", "UNSUPPORTED", "INSUFFICIENT_CONTEXT")));
        review.put(
                "reason_code",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "MATCHED_EXPRESSION",
                                "NO_MATCHING_EXPRESSION",
                                "STATUS_MISMATCH",
                                "INSUFFICIENT_CONTEXT")));
        review.put("explanation", Map.of("type", "string"));
        var root = new LinkedHashMap<String, Object>();
        root.put(
                "analysis_id",
                Map.of("type", "string", "enum", List.of(source.path("analysis_id").asString())));
        root.put("reviews", Map.of("type", "array", "items", object(review)));
        return EvalReport.JSON.writeValueAsString(object(root));
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
}
