package com.neuringo.neuringobe.ai.application.prompt;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 서버 소유 식별자는 요청에 바인딩하고 모델은 의미 판단과 실제 발화만 반환한다. */
public final class CharacterModelOutput {
    public static final String ANALYSIS_VERSION = "character-analysis-output/v1";
    public static final String CANDIDATE_VERSION = "character-candidate-output/v1";
    private final ObjectMapper json;
    private final ObjectNode schema;
    private final Schema validator;
    private final Map<String, GoalObservationInput.Criterion> bindings;

    private CharacterModelOutput(
            ObjectMapper json,
            Map<String, Object> properties,
            Map<String, GoalObservationInput.Criterion> bindings) {
        this.json = json;
        this.bindings = Map.copyOf(bindings);
        schema = (ObjectNode) json.valueToTree(object(properties));
        validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schema);
    }

    static CharacterModelOutput analysis(
            ObjectMapper json, RoleplayTurnInput input, GoalObservationInput observations) {
        var bindings = new LinkedHashMap<String, GoalObservationInput.Criterion>();
        var statuses = new LinkedHashMap<String, Object>();
        for (var criterion : observations.approvedCriteria()) {
            String key = "o" + bindings.size();
            bindings.put(key, criterion);
            statuses.put(
                    key,
                    enumeration(
                            List.of(
                                    "EXPLICIT",
                                    "IMPLICIT",
                                    "NOT_EXPRESSED",
                                    "MISUNDERSTOOD",
                                    "CONTRADICTORY")));
        }
        var properties = new LinkedHashMap<String, Object>();
        properties.put("analysis_basis", Map.of("type", "array", "items", string()));
        properties.put(
                "response_act",
                enumeration(RoleplayCodes.RESPONSE_ACTS.stream().sorted().toList()));
        properties.put(
                "relevance", enumeration(RoleplayCodes.RELEVANCE.stream().sorted().toList()));
        properties.put(
                "learning_state",
                enumeration(RoleplayCodes.LEARNING_STATES.stream().sorted().toList()));
        properties.put(
                "primary_gap",
                object(
                        Map.of(
                                "code",
                                enumeration(RoleplayCodes.GAP_CODES.stream().sorted().toList()),
                                "description",
                                string())));
        properties.put(
                "next_strategy",
                object(
                        Map.of(
                                "type",
                                enumeration(
                                        RoleplayCodes.STRATEGY_TYPES.stream().sorted().toList()),
                                "target_micro_goal_id",
                                enumeration(
                                        input.scenario().microGoals().stream()
                                                .map(g -> g.id().toString())
                                                .toList()),
                                "purpose",
                                string(),
                                "recommended_support_level",
                                enumeration(List.of("S0", "S1", "S2", "S3")))));
        properties.put("analysis_confidence", Map.of("type", "number"));
        properties.put("observations", object(statuses));
        properties.put(
                "activity_goal",
                object(
                        Map.of(
                                "decision",
                                enumeration(List.of("ACHIEVED", "IN_PROGRESS", "UNAVAILABLE")),
                                "basis",
                                Map.of(
                                        "type",
                                        "array",
                                        "items",
                                        object(
                                                Map.of(
                                                        "turn_id",
                                                        string(),
                                                        "micro_goal_id",
                                                        string(),
                                                        "criterion_reference",
                                                        string()))))));
        return new CharacterModelOutput(json, properties, bindings);
    }

    static CharacterModelOutput candidate(ObjectMapper json, boolean hintRequired) {
        return new CharacterModelOutput(
                json,
                Map.of(
                        "text",
                        string(),
                        "mascot_hint",
                        hintRequired ? string() : Map.of("type", "null")),
                Map.of());
    }

    Map<String, Object> tasks() {
        var tasks = new LinkedHashMap<String, Object>();
        bindings.keySet().stream()
                .sorted()
                .forEach(
                        key -> {
                            var criterion = bindings.get(key);
                            tasks.put(
                                    key,
                                    Map.of(
                                            "micro_goal_id",
                                            criterion.microGoalId(),
                                            "criterion_reference",
                                            criterion.reference()));
                        });
        return tasks;
    }

    String schema() {
        return schema.toString();
    }

    LlmOutputParser<AnalysisResult> analysisParser(
            AiTraceContext trace, LlmOutputParser<AnalysisResult> delegate) {
        return content -> {
            var output = validated(content);
            var restored = output.deepCopy();
            var statuses = output.path("observations");
            var activity = output.path("activity_goal");
            restored.remove("observations");
            restored.remove("activity_goal");
            restored.put("turn_id", trace.turnId().toString());
            var observation = restored.putObject("goal_observation");
            observation.put("schema_version", GoalObservationOutput.ANALYSIS_VERSION);
            observation.put("analysis_id", trace.analysisId().toString());
            var rows = observation.putArray("observations");
            bindings.keySet().stream()
                    .sorted()
                    .forEach(
                            key -> {
                                var criterion = bindings.get(key);
                                var row = rows.addObject();
                                row.set(
                                        "reference",
                                        json.valueToTree(
                                                new GoalObservationOutput.Reference(
                                                        trace.turnId(),
                                                        criterion.microGoalId(),
                                                        criterion.reference())));
                                row.set("status", statuses.path(key));
                            });
            observation.set("activity_goal", activity);
            return delegate.parse(restored.toString());
        };
    }

    LlmOutputParser<CandidateResponse> candidateParser(
            UUID candidateId,
            AnalysisResult analysis,
            List<UUID> failedIds,
            LlmOutputParser<CandidateResponse> delegate) {
        return content -> {
            var output = validated(content);
            var restored = output.deepCopy();
            restored.put("candidate_id", candidateId.toString());
            restored.put("turn_id", analysis.turnId().toString());
            restored.put(
                    "response_type",
                    CharacterResponseContract.from(analysis).expectedResponseType());
            restored.put("strategy_used", analysis.nextStrategy().type());
            restored.put(
                    "target_micro_goal_id", analysis.nextStrategy().targetMicroGoalId().toString());
            restored.put("support_level", analysis.nextStrategy().recommendedSupportLevel());
            restored.set("previous_failed_candidate_ids", json.valueToTree(failedIds));
            return delegate.parse(restored.toString());
        };
    }

    private ObjectNode validated(String content) {
        try {
            JsonNode output = json.readTree(content);
            if (!(output instanceof ObjectNode object) || !validator.validate(object).isEmpty())
                throw new InvalidLlmOutputException("간결 캐릭터 출력이 요청 스키마와 다릅니다.", null);
            return object;
        } catch (InvalidLlmOutputException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new InvalidLlmOutputException("간결 캐릭터 출력이 요청 스키마와 다릅니다.", null);
        }
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

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> enumeration(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }
}
