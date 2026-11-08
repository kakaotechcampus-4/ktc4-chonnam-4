package com.neuringo.neuringobe.ai.application.prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 평가 필드의 공통 스키마 조각을 생성한다. 요청별 제약은 별도로 결합한다. */
final class EvaluationSchemaParts {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final tools.jackson.databind.JsonNode PARTS =
            JSON.readTree(create()).path("properties");

    private EvaluationSchemaParts() {}

    static tools.jackson.databind.JsonNode properties() {
        return PARTS.deepCopy();
    }

    private static String create() {
        Map<String, Object> checks = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "format_valid",
                        "safety_valid",
                        "privacy_valid",
                        "scenario_consistent",
                        "analysis_response_consistent",
                        "goal_aligned",
                        "educationally_correct",
                        "difficulty_appropriate",
                        "conversation_coherent",
                        "answerable",
                        "non_repetitive")) checks.put(key, Map.of("type", "boolean"));
        Map<String, Object> revision = new LinkedHashMap<>();
        for (String key : List.of("keep", "change", "avoid", "required"))
            revision.put(key, Map.of("type", "array", "items", Map.of("type", "string")));
        var nullableRevision = new LinkedHashMap<>(object(revision));
        nullableRevision.put("type", List.of("object", "null"));

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("analysis_id", Map.of("type", "string"));
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put(
                "reviewed_decision",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("ACHIEVED", "IN_PROGRESS", "UNAVAILABLE")));
        activity.put("verdict", verdictSchema());
        activity.put(
                "reason_code", Map.of("type", "string", "enum", AnalysisReview.ACTIVITY_REASONS));
        var reference = new LinkedHashMap<String, Object>();
        for (String key : List.of("turn_id", "micro_goal_id", "criterion_reference"))
            reference.put(key, Map.of("type", "string"));
        activity.put("evidence_references", Map.of("type", "array", "items", object(reference)));
        activity.put("explanation", Map.of("type", "string"));
        review.put("activity_review", nullableObject(activity));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("candidate_id", Map.of("type", "string"));
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("micro_goal_id", Map.of("type", "string"));
        observation.put("criterion_reference", Map.of("type", "string"));
        observation.put("evidence_quote", Map.of("type", List.of("string", "null")));
        observation.put(
                "verdict",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("SUPPORTED", "UNSUPPORTED", "INSUFFICIENT_CONTEXT")));
        observation.put(
                "reason_code",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "MATCHED_EXPRESSION",
                                "ABSENCE_CONFIRMED",
                                "NO_MATCHING_EXPRESSION",
                                "STATUS_MISMATCH",
                                "INSUFFICIENT_CONTEXT")));
        observation.put("explanation", Map.of("type", "string"));
        output.put("observation_reviews", Map.of("type", "array", "items", object(observation)));
        output.put("checks", object(checks));
        var logic = new LinkedHashMap<String, Object>();
        logic.put("verdict", verdictSchema());
        logic.put("reason_code", Map.of("type", "string", "enum", AnalysisReview.LOGIC_REASONS));
        logic.put("explanation", Map.of("type", "string"));
        output.put("analysis_logic_review", nullableObject(logic));
        output.put(
                "failure_codes",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string", "enum", modelFailureCodes())));
        output.put(
                "decision",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of(
                                "PASS",
                                "REANALYZE",
                                "REGENERATE",
                                "SAFETY_REGENERATE",
                                "CONFIRM_INPUT",
                                "SAFETY_ESCALATION")));
        output.put("revision_instruction", nullableRevision);
        output.put("goal_observation_review", object(review));
        return JSON.writeValueAsString(object(output));
    }

    static List<String> modelFailureCodes() {
        return RoleplayCodes.FAILURE_CODES.stream()
                .filter(code -> !AnalysisReview.SERVER_CODES.contains(code))
                .sorted()
                .toList();
    }

    private static Map<String, Object> verdictSchema() {
        return Map.of(
                "type",
                "string",
                "enum",
                List.of("SUPPORTED", "UNSUPPORTED", "INSUFFICIENT_CONTEXT"));
    }

    private static Map<String, Object> nullableObject(Map<String, Object> properties) {
        var result = new LinkedHashMap<>(object(properties));
        result.put("type", List.of("object", "null"));
        return result;
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }
}
