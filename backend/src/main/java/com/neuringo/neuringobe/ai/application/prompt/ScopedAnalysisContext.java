package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 요청별 검수 키와 목표 범위를 고정하고 원본 참조로 복원해 운영 검증기에 전달한다. */
public final class ScopedAnalysisContext {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JsonNode original;
    private final ObjectNode tasks;
    private final ObjectNode payload;
    private final String schema;
    private final ObservationReviewValidator observationsValidator;
    private final AnalysisResult analysis;
    private final GoalObservationInput observations;
    private final UUID currentGoalId;

    public ScopedAnalysisContext(
            JsonNode source,
            RoleplayTurnInput input,
            AnalysisResult result,
            GoalObservationInput observationInput) {
        original = source.deepCopy();
        analysis = result;
        observations = observationInput;
        currentGoalId = input.state().currentMicroGoalId();
        observationsValidator =
                new ObservationReviewValidator(input, result.goalObservation(), observationInput);
        payload = ((ObjectNode) original).deepCopy();
        payload.remove("candidate_response");
        payload.remove("analysis_result");
        tasks = payload.putObject("observation_tasks");
        var analysis = original.path("analysis_result");
        int index = 0;
        for (var observation : analysis.path("goal_observation").path("observations")) {
            var ref = observation.path("reference");
            var task = tasks.putObject("o" + index++);
            task.set("reference", ref);
            task.set("status", observation.path("status"));
            task.set("goal_description", goal(ref.path("micro_goal_id")).path("description"));
            JsonNode criterion = null;
            for (var c : original.path("goal_observation_context").path("approved_criteria")) {
                if (c.path("micro_goal_id").equals(ref.path("micro_goal_id"))
                        && c.path("reference").equals(ref.path("criterion_reference")))
                    criterion = c;
            }
            task.set(
                    "criterion_description",
                    java.util.Objects.requireNonNull(criterion).path("description"));
        }
        var current = goal(original.path("state").path("current_micro_goal_id"));
        var focus = payload.putObject("current_goal_assessment");
        focus.set("micro_goal_id", current.path("id"));
        focus.set("goal_description", current.path("description"));
        focus.set("required_evidence", current.path("required_evidence"));
        focus.set("proposed_learning_state", analysis.path("learning_state"));
        var activity = payload.putObject("activity_assessment");
        activity.set("learning_goal", original.path("scenario").path("learning_goal"));
        activity.set(
                "proposed_decision",
                analysis.path("goal_observation").path("activity_goal").path("decision"));
        activity.set(
                "basis", analysis.path("goal_observation").path("activity_goal").path("basis"));
        var logic = ((ObjectNode) analysis).deepCopy();
        logic.remove("learning_state");
        logic.remove("goal_observation");
        payload.set("interpretation_strategy", logic);
        schema = createSchema();
    }

    private JsonNode goal(JsonNode id) {
        for (var goal : original.path("scenario").path("micro_goals"))
            if (goal.path("id").equals(id)) return goal;
        throw new IllegalArgumentException("검수할 현재 목표가 승인 목록에 없습니다.");
    }

    AnalysisResult reviewedAnalysis() {
        return analysis;
    }

    public JsonNode input() {
        return payload.deepCopy();
    }

    public JsonNode bindings() {
        return tasks.deepCopy();
    }

    public String schema() {
        return schema;
    }

    /** 운영 평가에는 관찰 상태별 허용 조합을 추가하며 과거 분석 전용 계약은 유지한다. */
    public String evaluationSchema() {
        var root = (ObjectNode) JSON.readTree(schema);
        var reviews =
                (ObjectNode) root.path("properties").path("observation_reviews").path("properties");
        for (String key : tasks.propertyNames()) {
            var template = (ObjectNode) reviews.path(key);
            var constrained = JSON.createObjectNode();
            var choices = constrained.putArray("anyOf");
            boolean absent = tasks.path(key).path("status").asString().equals("NOT_EXPRESSED");
            choices.add(
                    observationChoice(
                            template,
                            "SUPPORTED",
                            absent ? "ABSENCE_CONFIRMED" : "MATCHED_EXPRESSION",
                            absent));
            choices.add(observationChoice(template, "UNSUPPORTED", "STATUS_MISMATCH", false));
            if (!absent)
                choices.add(
                        observationChoice(template, "UNSUPPORTED", "NO_MATCHING_EXPRESSION", true));
            choices.add(
                    observationChoice(
                            template, "INSUFFICIENT_CONTEXT", "INSUFFICIENT_CONTEXT", true));
            reviews.set(key, constrained);
        }
        return root.toString();
    }

    private static ObjectNode observationChoice(
            ObjectNode template, String verdict, String reason, boolean nullableQuote) {
        var choice = template.deepCopy();
        var properties = (ObjectNode) choice.path("properties");
        properties.set("verdict", stringEnum(List.of(verdict)));
        properties.set("reason_code", stringEnum(List.of(reason)));
        var quote = JSON.createObjectNode();
        if (nullableQuote) quote.set("type", JSON.valueToTree(List.of("string", "null")));
        else quote.put("type", "string");
        properties.set("evidence_quote", quote);
        return choice;
    }

    public JsonNode restoreAnalysis(JsonNode input) {
        var result = ((ObjectNode) input.path("interpretation_strategy")).deepCopy();
        result.set(
                "learning_state",
                input.path("current_goal_assessment").path("proposed_learning_state"));
        var observation = result.putObject("goal_observation");
        observation.set(
                "schema_version",
                original.path("analysis_result").path("goal_observation").path("schema_version"));
        observation.set(
                "analysis_id",
                original.path("analysis_result").path("goal_observation").path("analysis_id"));
        var rows = observation.putArray("observations");
        for (var task : input.path("observation_tasks")) {
            var row = rows.addObject();
            row.set("reference", task.path("reference"));
            row.set("status", task.path("status"));
        }
        var activity = observation.putObject("activity_goal");
        activity.set("decision", input.path("activity_assessment").path("proposed_decision"));
        activity.set("basis", input.path("activity_assessment").path("basis"));
        return result;
    }

    private String createSchema() {
        var old = EvaluationSchemaParts.properties();
        var root = object();
        var properties = (ObjectNode) root.path("properties");
        properties.set("analysis_id", singleton(original.path("analysis_id")));
        var reviews = object();
        var item = (ObjectNode) old.path("observation_reviews").path("items").deepCopy();
        ((ObjectNode) item.path("properties")).remove("micro_goal_id");
        ((ObjectNode) item.path("properties")).remove("criterion_reference");
        required(item);
        tasks.propertyNames()
                .forEach(
                        key -> ((ObjectNode) reviews.path("properties")).set(key, item.deepCopy()));
        required(reviews);
        properties.set("observation_reviews", reviews);
        var current = (ObjectNode) old.path("analysis_logic_review").deepCopy();
        current.put("type", "object");
        var cp = (ObjectNode) current.path("properties");
        cp.set(
                "reviewed_micro_goal_id",
                singleton(payload.path("current_goal_assessment").path("micro_goal_id")));
        cp.set(
                "reviewed_learning_state",
                singleton(payload.path("current_goal_assessment").path("proposed_learning_state")));
        cp.set(
                "reason_code",
                stringEnum(
                        List.of(
                                "LOGIC_GROUNDED",
                                "LEARNING_STATE_MISMATCH",
                                "INSUFFICIENT_CONTEXT")));
        required(current);
        properties.set("current_goal_review", current);
        var activity =
                (ObjectNode)
                        old.path("goal_observation_review")
                                .path("properties")
                                .path("activity_review")
                                .deepCopy();
        activity.put("type", "object");
        ((ObjectNode) activity.path("properties"))
                .set(
                        "reviewed_decision",
                        singleton(payload.path("activity_assessment").path("proposed_decision")));
        properties.set("activity_review", activity);
        var logic = (ObjectNode) old.path("analysis_logic_review").deepCopy();
        logic.put("type", "object");
        ((ObjectNode) logic.path("properties"))
                .set(
                        "reason_code",
                        stringEnum(
                                AnalysisReview.LOGIC_REASONS.stream()
                                        .filter(reason -> !reason.equals("LEARNING_STATE_MISMATCH"))
                                        .toList()));
        properties.set("interpretation_strategy_review", logic);
        required(root);
        return root.toString();
    }

    private static ObjectNode object() {
        var result =
                JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        result.putObject("properties");
        return result;
    }

    private static void required(ObjectNode object) {
        var required = object.putArray("required");
        object.path("properties").propertyNames().forEach(required::add);
    }

    private static ObjectNode singleton(JsonNode value) {
        return stringEnum(List.of(value.asString()));
    }

    private static ObjectNode stringEnum(List<String> values) {
        var result = JSON.createObjectNode().put("type", "string");
        result.set("enum", JSON.valueToTree(values));
        return result;
    }

    public Approvals validate(JsonNode output) {
        var current = JSON.treeToValue(output.path("current_goal_review"), CurrentGoalReview.class);
        if (current == null
                || !currentGoalId.equals(current.reviewedMicroGoalId())
                || !analysis.learningState().equals(current.reviewedLearningState())) {
            throw new InvalidLlmOutputException("current goal review targets do not match", null);
        }
        var currentVerdict = AnalysisReview.logic(current.logic());
        var activity =
                AnalysisReview.activity(
                        JSON.treeToValue(
                                output.path("activity_review"), AnalysisReview.Activity.class),
                        analysis.goalObservation(),
                        observations);
        var logic =
                AnalysisReview.logic(
                        JSON.treeToValue(
                                output.path("interpretation_strategy_review"),
                                AnalysisReview.Logic.class));
        var reviews = output.path("observation_reviews");
        if (!reviews.isObject() || !reviews.propertyNames().equals(tasks.propertyNames())) {
            throw new InvalidLlmOutputException("observation review keys do not match", null);
        }
        var rows = new ArrayList<ObservationReview>();
        for (String key : tasks.propertyNames()) {
            var review = (ObjectNode) reviews.path(key).deepCopy();
            var ref = tasks.path(key).path("reference");
            review.set("micro_goal_id", ref.path("micro_goal_id"));
            review.set("criterion_reference", ref.path("criterion_reference"));
            rows.add(JSON.treeToValue(review, ObservationReview.class));
        }
        boolean observed = observationsValidator.validate(rows);
        return new Approvals(
                observed,
                AnalysisReview.supported(currentVerdict),
                AnalysisReview.supported(activity),
                AnalysisReview.supported(logic));
    }

    public record Approvals(
            boolean observationsSupported,
            boolean currentGoalSupported,
            boolean activitySupported,
            boolean interpretationStrategySupported) {
        boolean allSupported() {
            return observationsSupported
                    && currentGoalSupported
                    && activitySupported
                    && interpretationStrategySupported;
        }
    }

    record CurrentGoalReview(
            @JsonProperty("reviewed_micro_goal_id") UUID reviewedMicroGoalId,
            @JsonProperty("reviewed_learning_state") String reviewedLearningState,
            ObservationReview.Verdict verdict,
            @JsonProperty("reason_code") String reasonCode,
            String explanation) {
        AnalysisReview.Logic logic() {
            return new AnalysisReview.Logic(verdict, reasonCode, explanation);
        }
    }
}
