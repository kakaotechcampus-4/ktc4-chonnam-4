package com.neuringo.neuringobe.ai.application.prompt;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 분석 입력은 허용된 필드로만 구성하고 실제 승인 결과와 후보 검수 결과를 별도로 연결한다. */
public final class SplitReviewValidation {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ANALYSIS_FIELDS =
            List.of(
                    "scenario",
                    "state",
                    "recent_dialogue",
                    "learner_turn",
                    "analysis_id",
                    "goal_observation_context",
                    "independence_review_context",
                    "goal_evidence_so_far",
                    "analysis_result");
    private final ObjectNode source;
    private final ScopedAnalysisValidation analysis;

    public SplitReviewValidation(JsonNode original) {
        source = JSON.createObjectNode();
        for (String key : ANALYSIS_FIELDS) {
            if (!original.has(key)) throw new IllegalArgumentException("분석 입력 필드가 누락됐습니다.");
            source.set(key, original.path(key).deepCopy());
        }
        analysis = new ScopedAnalysisValidation(source);
    }

    public JsonNode analysisInput() {
        return analysis.input();
    }

    public String analysisSchema() {
        return analysis.schema();
    }

    public JsonNode bindings() {
        return analysis.bindings();
    }

    public JsonNode restoreAnalysis(JsonNode input) {
        return analysis.restoreAnalysis(input);
    }

    public ScopedAnalysisValidation.Checks validateAnalysis(String raw) {
        return analysis.validate(raw);
    }

    public CandidateReview candidateReview(String analysisRaw, JsonNode candidate) {
        var checked = analysis.validate(analysisRaw);
        if (!"VALID_REVIEW".equals(checked.outcome())
                || !Boolean.TRUE.equals(checked.observationsSupported())
                || !Boolean.TRUE.equals(checked.currentGoalSupported())
                || !Boolean.TRUE.equals(checked.activitySupported())
                || !Boolean.TRUE.equals(checked.interpretationStrategySupported())) {
            throw new InvalidLlmOutputException("독립 검수를 통과한 분석이 필요합니다.", null);
        }
        return new CandidateReview(JSON.readTree(analysisRaw), candidate.deepCopy());
    }

    public final class CandidateReview {
        private final JsonNode actualAnalysisReview;
        private final ObjectNode payload;
        private final String schema;
        private final com.networknt.schema.Schema validator;
        private final UUID analysisId;
        private final UUID candidateId;

        private CandidateReview(JsonNode actualReview, JsonNode candidate) {
            actualAnalysisReview = actualReview.deepCopy();
            analysisId = UUID.fromString(source.path("analysis_id").asString());
            candidateId = UUID.fromString(candidate.path("candidate_id").asString());
            payload = (ObjectNode) analysisInput();
            payload.set("candidate_response", candidate.deepCopy());
            payload.put("analysis_review_approved", true);
            var properties = new LinkedHashMap<String, Object>();
            properties.put("analysis_id", singleton(analysisId));
            properties.put("candidate_id", singleton(candidateId));
            var parts = EvaluationSchemaParts.properties();
            for (String key : List.of("checks", "failure_codes", "revision_instruction"))
                properties.put(key, parts.path(key));
            properties.put(
                    "decision",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of(
                                    "PASS",
                                    "REGENERATE",
                                    "SAFETY_REGENERATE",
                                    "CONFIRM_INPUT",
                                    "SAFETY_ESCALATION")));
            schema =
                    JSON.writeValueAsString(
                            Map.of(
                                    "type",
                                    "object",
                                    "additionalProperties",
                                    false,
                                    "properties",
                                    properties,
                                    "required",
                                    List.copyOf(properties.keySet())));
            validator =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(JSON.readTree(schema));
        }

        public JsonNode input() {
            return payload.deepCopy();
        }

        public String schema() {
            return schema;
        }

        public EvaluationResult validate(String raw) {
            var output = JSON.readTree(raw);
            if (output == null || !validator.validate(output).isEmpty())
                throw new InvalidLlmOutputException("candidate review does not match schema", null);
            var decision = EvaluationDecision.valueOf(output.path("decision").asString());
            var codes = JSON.convertValue(output.path("failure_codes"), String[].class);
            var combined =
                    new EvaluationOutput(
                            candidateId,
                            decision,
                            JSON.treeToValue(output.path("checks"), EvaluationOutput.Checks.class),
                            analysisId,
                            JSON.treeToValue(
                                    actualAnalysisReview.path("current_goal_review"),
                                    ScopedAnalysisContext.CurrentGoalReview.class),
                            JSON.treeToValue(
                                    actualAnalysisReview.path("activity_review"),
                                    AnalysisReview.Activity.class),
                            JSON.treeToValue(
                                    actualAnalysisReview.path("interpretation_strategy_review"),
                                    AnalysisReview.Logic.class),
                            observationReviews(),
                            List.of(codes),
                            output.path("revision_instruction").isNull()
                                    ? null
                                    : JSON.treeToValue(
                                            output.path("revision_instruction"),
                                            RevisionInstruction.class),
                            null,
                            null,
                            null);
            boolean priority =
                    decision == EvaluationDecision.SAFETY_ESCALATION
                            || decision == EvaluationDecision.CONFIRM_INPUT;
            var approvals =
                    new ScopedAnalysisContext.Approvals(!priority, !priority, !priority, !priority);
            var result = combined.toResult(approvals);
            // 내부 결과의 기존 전달·실패 코드 조건도 조합 후 유지한다.
            if (decision == EvaluationDecision.PASS) {
                if (!result.canDeliver() || !result.failureCodes().isEmpty())
                    throw new InvalidLlmOutputException(
                            "PASS must be safe with no failure codes", null);
            } else if (result.failureCodes().isEmpty()) {
                throw new InvalidLlmOutputException(
                        "a failed evaluation needs failure codes", null);
            }
            return result;
        }

        public boolean schemaValid(String raw) {
            try {
                var node = JSON.readTree(raw);
                return node != null && validator.validate(node).isEmpty();
            } catch (RuntimeException failure) {
                return false;
            }
        }

        private Map<String, JsonNode> observationReviews() {
            var rows = new LinkedHashMap<String, JsonNode>();
            for (String key : actualAnalysisReview.path("observation_reviews").propertyNames())
                rows.put(
                        key, actualAnalysisReview.path("observation_reviews").path(key).deepCopy());
            return Map.copyOf(rows);
        }
    }

    private static Map<String, Object> singleton(UUID id) {
        return Map.of("type", "string", "enum", List.of(id.toString()));
    }
}
