package com.neuringo.neuringobe.ai.application.prompt;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 요청의 바인딩과 같은 스키마를 제공자 제약과 수신 검증에 사용한다. */
final class EvaluationOutputSchema {
    static final String VERSION = "evaluation-output/v5";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String jsonSchema;
    private final Schema validator;

    EvaluationOutputSchema(ScopedAnalysisContext context, UUID candidateId) {
        var schema = (ObjectNode) JSON.readTree(context.evaluationSchema());
        var properties = (ObjectNode) schema.path("properties");
        for (String key :
                List.of(
                        "observation_reviews",
                        "current_goal_review",
                        "activity_review",
                        "interpretation_strategy_review")) {
            ((ObjectNode) properties.path(key))
                    .set("type", JSON.valueToTree(List.of("object", "null")));
        }
        properties.set(
                "candidate_id",
                JSON.valueToTree(
                        java.util.Map.of(
                                "type", "string", "enum", List.of(candidateId.toString()))));
        var parts = EvaluationSchemaParts.properties();
        for (String key : List.of("checks", "decision", "failure_codes", "revision_instruction"))
            properties.set(key, parts.path(key));
        var required = schema.putArray("required");
        properties.propertyNames().forEach(required::add);
        jsonSchema = schema.toString();
        validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schema);
    }

    String jsonSchema() {
        return jsonSchema;
    }

    void validate(String content) {
        var node = JSON.readTree(content);
        if (node == null || !validator.validate(node).isEmpty()) {
            if (node != null && node.path("failure_codes").isArray()) {
                for (var code : node.path("failure_codes")) {
                    if (code.isString() && !RoleplayCodes.FAILURE_CODES.contains(code.asString()))
                        throw new InvalidLlmOutputException("unknown failure code", null);
                }
            }
            throw new InvalidLlmOutputException("evaluation output does not match schema", null);
        }
    }

    static List<String> modelFailureCodes() {
        return EvaluationSchemaParts.modelFailureCodes();
    }
}
