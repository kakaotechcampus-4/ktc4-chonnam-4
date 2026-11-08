package com.neuringo.neuringobe.ai.application.prompt;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.structured.IndeterminateLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 운영 검증기를 직접 사용하는 진단 전용 어댑터다. 후보 평가나 전달 판정을 만들지 않는다. */
public final class AnalysisIsolationValidation {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
    private static final List<String> FIELDS =
            List.of("observation_reviews", "goal_observation_review", "analysis_logic_review");
    private static final String SCHEMA = createSchema();
    private static final com.networknt.schema.Schema VALIDATOR =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(SCHEMA));
    private final ObservationReviewValidator observationsValidator;
    private final GoalObservationOutput.Analysis analysis;
    private final GoalObservationInput observations;

    public AnalysisIsolationValidation(JsonNode input) {
        var restored = JSON.createObjectNode();
        for (String key : List.of("scenario", "state", "learner_turn", "recent_dialogue"))
            restored.set(key, input.path(key));
        // 기존 평가 입력에 없는 내부 기본값이며 모델 요청에는 추가하지 않는다.
        restored.set(
                "state",
                ((ObjectNode) input.path("state"))
                        .deepCopy()
                        .put("current_micro_goal_mascot_hints", 0)
                        .put("previous_turn_had_mascot_hint", false));
        var turn = JSON.treeToValue(restored, RoleplayTurnInput.class);
        analysis =
                JSON.treeToValue(
                        input.path("analysis_result").path("goal_observation"),
                        GoalObservationOutput.Analysis.class);
        observations =
                JSON.treeToValue(
                        input.path("goal_observation_context"), GoalObservationInput.class);
        observationsValidator = new ObservationReviewValidator(turn, analysis, observations);
    }

    public static String schema() {
        return SCHEMA;
    }

    private static String createSchema() {
        try (var stream =
                AnalysisIsolationValidation.class.getResourceAsStream(
                        "/eval/analysis-isolation-v1.schema.json")) {
            return new String(
                    java.util.Objects.requireNonNull(stream).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private record GoalReview(java.util.UUID analysisId, AnalysisReview.Activity activityReview) {}

    public Checks validate(String raw) {
        JsonNode output;
        try {
            output = JSON.readTree(raw);
            if (output == null || !VALIDATOR.validate(output).isEmpty())
                return new Checks(
                        "SCHEMA_FAILURE",
                        false,
                        null,
                        null,
                        null,
                        "analysis isolation output does not match schema");
        } catch (RuntimeException failure) {
            return new Checks(
                    "SCHEMA_FAILURE",
                    false,
                    null,
                    null,
                    null,
                    "analysis isolation output does not match schema");
        }
        try {
            var goal = JSON.treeToValue(output.path("goal_observation_review"), GoalReview.class);
            if (!analysis.analysisId().equals(goal.analysisId()))
                throw new InvalidLlmOutputException("다른 분석에 대한 목표 관찰 검수입니다.", null);
            var activity = AnalysisReview.activity(goal.activityReview(), analysis, observations);
            var logic =
                    AnalysisReview.logic(
                            JSON.treeToValue(
                                    output.path("analysis_logic_review"),
                                    AnalysisReview.Logic.class));
            List<ObservationReview> rows =
                    JSON.readerForListOf(ObservationReview.class)
                            .readValue(output.path("observation_reviews").toString());
            boolean observed = observationsValidator.validate(rows);
            return new Checks(
                    "VALID_REVIEW",
                    true,
                    observed,
                    AnalysisReview.supported(activity),
                    AnalysisReview.supported(logic),
                    null);
        } catch (IndeterminateLlmOutputException failure) {
            return new Checks(
                    "INDETERMINATE_OUTPUT",
                    true,
                    null,
                    null,
                    null,
                    "evaluation review has insufficient context");
        } catch (InvalidLlmOutputException failure) {
            return new Checks("INVALID_EVIDENCE", true, null, null, null, failure.getMessage());
        }
    }

    public record Checks(
            String outcome,
            boolean schemaValid,
            Boolean observationsSupported,
            Boolean activitySupported,
            Boolean logicSupported,
            String reason) {
        public boolean expectedAssessmentMatches() {
            return outcome.equals("VALID_REVIEW")
                    && Boolean.TRUE.equals(observationsSupported)
                    && Boolean.TRUE.equals(activitySupported)
                    && Boolean.TRUE.equals(logicSupported);
        }
    }
}
