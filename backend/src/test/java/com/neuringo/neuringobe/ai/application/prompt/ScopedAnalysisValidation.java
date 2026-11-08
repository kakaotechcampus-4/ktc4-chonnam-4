package com.neuringo.neuringobe.ai.application.prompt;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.structured.IndeterminateLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 과거 분석 전용 진단의 계약을 유지하며 운영의 범위별 검증기를 사용한다. */
public final class ScopedAnalysisValidation {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
    private final ScopedAnalysisContext context;
    private final com.networknt.schema.Schema validator;

    public ScopedAnalysisValidation(JsonNode original) {
        var restored = JSON.createObjectNode();
        for (String key : List.of("scenario", "state", "learner_turn", "recent_dialogue"))
            restored.set(key, original.path(key));
        restored.set(
                "state",
                ((ObjectNode) original.path("state"))
                        .deepCopy()
                        .put("current_micro_goal_mascot_hints", 0)
                        .put("previous_turn_had_mascot_hint", false));
        context =
                new ScopedAnalysisContext(
                        original,
                        JSON.treeToValue(restored, RoleplayTurnInput.class),
                        JSON.treeToValue(original.path("analysis_result"), AnalysisResult.class),
                        JSON.treeToValue(
                                original.path("goal_observation_context"),
                                GoalObservationInput.class));
        validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(JSON.readTree(context.schema()));
    }

    public JsonNode input() {
        return context.input();
    }

    public JsonNode bindings() {
        return context.bindings();
    }

    public String schema() {
        return context.schema();
    }

    public JsonNode restoreAnalysis(JsonNode input) {
        return context.restoreAnalysis(input);
    }

    public Checks validate(String raw) {
        JsonNode output;
        try {
            output = JSON.readTree(raw);
            if (output == null || !validator.validate(output).isEmpty())
                return failed("SCHEMA_FAILURE", false, "scoped review does not match schema");
        } catch (RuntimeException failure) {
            return failed("SCHEMA_FAILURE", false, "scoped review does not match schema");
        }
        try {
            var approvals = context.validate(output);
            var verdicts = new ArrayList<ObservationVerdict>();
            for (String key : context.bindings().propertyNames())
                verdicts.add(
                        new ObservationVerdict(
                                key,
                                output.path("observation_reviews")
                                        .path(key)
                                        .path("verdict")
                                        .asString()));
            return new Checks(
                    "VALID_REVIEW",
                    true,
                    approvals.observationsSupported(),
                    approvals.currentGoalSupported(),
                    approvals.activitySupported(),
                    approvals.interpretationStrategySupported(),
                    List.copyOf(verdicts),
                    null);
        } catch (IndeterminateLlmOutputException failure) {
            return failed(
                    "INDETERMINATE_OUTPUT", true, "evaluation review has insufficient context");
        } catch (InvalidLlmOutputException failure) {
            return failed("INVALID_EVIDENCE", true, failure.getMessage());
        }
    }

    private static Checks failed(String outcome, boolean schemaValid, String reason) {
        return new Checks(outcome, schemaValid, null, null, null, null, List.of(), reason);
    }

    public record ObservationVerdict(String key, String verdict) {}

    public record Checks(
            String outcome,
            boolean schemaValid,
            Boolean observationsSupported,
            Boolean currentGoalSupported,
            Boolean activitySupported,
            Boolean interpretationStrategySupported,
            List<ObservationVerdict> observationVerdicts,
            String reason) {}
}
