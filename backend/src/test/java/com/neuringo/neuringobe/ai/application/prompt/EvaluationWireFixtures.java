package com.neuringo.neuringobe.ai.application.prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 합성 제공자의 기존 판정을 현재 요청의 관찰·발화에 연결한다. 실제 모델 원문 재생에는 사용하지 않는다. */
public final class EvaluationWireFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EvaluationWireFixtures() {}

    public static String forRequest(String content, JsonNode request) {
        if (request.has("observation_tasks")) return scoped(content, request);
        Map<String, Object> body = JSON.readValue(content, Map.class);
        body.putIfAbsent("answer_directness_violation", null);
        body.putIfAbsent("goal_progress_violation", null);
        var checks = (Map<String, Object>) body.get("checks");
        Object legacyLogic = checks.remove("analysis_supported");
        if (legacyLogic != null || !body.containsKey("analysis_logic_review")) {
            body.put("analysis_logic_review", logicReview(!Boolean.FALSE.equals(legacyLogic)));
        }
        if (body.get("goal_observation_review") instanceof Map<?, ?> original) {
            var review = new LinkedHashMap<String, Object>((Map<String, Object>) original);
            boolean approved = !Boolean.FALSE.equals(review.remove("observations_supported"));
            Object legacyActivity = review.remove("activity_goal_supported");
            review.remove("schema_version");
            boolean activityApproved =
                    legacyActivity == null
                            ? !(review.get("activity_review") instanceof Map<?, ?> current)
                                    || !"UNSUPPORTED".equals(current.get("verdict"))
                            : !Boolean.FALSE.equals(legacyActivity);
            review.put("activity_review", activityReview(request, activityApproved));
            body.put("goal_observation_review", review);
            body.put("observation_reviews", reviews(request, approved));
        }
        body.put(
                "failure_codes",
                ((List<String>) body.get("failure_codes"))
                        .stream()
                                .filter(
                                        code ->
                                                !List.of(
                                                                "UNSUPPORTED_CAUSE_ANALYSIS",
                                                                "STATE_STRATEGY_MISMATCH")
                                                        .contains(code))
                                .toList());
        return JSON.writeValueAsString(body);
    }

    private static String scoped(String content, JsonNode request) {
        Map<String, Object> body = JSON.readValue(content, Map.class);
        body.putIfAbsent("answer_directness_violation", null);
        body.putIfAbsent("goal_progress_violation", null);
        var checks = (Map<String, Object>) body.get("checks");
        Object legacyLogic = checks.remove("analysis_supported");
        var old = body.remove("goal_observation_review");
        var goal = old instanceof Map<?, ?> map ? map : Map.of();
        boolean observed = !Boolean.FALSE.equals(goal.get("observations_supported"));
        if (body.get("observation_reviews") instanceof Map<?, ?> rows) {
            observed &=
                    rows.values().stream()
                            .noneMatch(
                                    row ->
                                            row instanceof Map<?, ?> r
                                                    && "UNSUPPORTED".equals(r.get("verdict")));
        }
        var oldLogic = body.remove("analysis_logic_review");
        var logic = oldLogic instanceof Map<?, ?> map ? map : Map.of();
        boolean currentSupported =
                !Boolean.FALSE.equals(legacyLogic)
                        && !("UNSUPPORTED".equals(logic.get("verdict"))
                                && "LEARNING_STATE_MISMATCH".equals(logic.get("reason_code")));
        var current =
                body.get("current_goal_review") instanceof Map<?, ?> map
                        ? new LinkedHashMap<String, Object>((Map<String, Object>) map)
                        : new LinkedHashMap<String, Object>(logicReview(currentSupported));
        if (!currentSupported) current.putAll(logicReview(false));
        current.put(
                "reviewed_micro_goal_id",
                request.path("current_goal_assessment").path("micro_goal_id").asString());
        current.put(
                "reviewed_learning_state",
                request.path("current_goal_assessment").path("proposed_learning_state").asString());
        body.put("current_goal_review", current);
        if (!body.containsKey("interpretation_strategy_review"))
            body.put(
                    "interpretation_strategy_review",
                    "LEARNING_STATE_MISMATCH".equals(logic.get("reason_code")) || logic.isEmpty()
                            ? logicReview(true)
                            : logic);
        Object oldActivity = goal.get("activity_review");
        boolean activitySupported = !Boolean.FALSE.equals(goal.get("activity_goal_supported"));
        if (oldActivity instanceof Map<?, ?> map)
            activitySupported &= !"UNSUPPORTED".equals(map.get("verdict"));
        if (body.get("activity_review") instanceof Map<?, ?> map)
            activitySupported &= !"UNSUPPORTED".equals(map.get("verdict"));
        body.put("activity_review", activityReview(request, activitySupported));
        body.put("analysis_id", request.path("analysis_id").asString());
        body.put("observation_reviews", scopedReviews(request, observed));
        body.put(
                "failure_codes",
                ((List<String>) body.get("failure_codes"))
                        .stream()
                                .filter(
                                        code ->
                                                !List.of(
                                                                "UNSUPPORTED_CAUSE_ANALYSIS",
                                                                "STATE_STRATEGY_MISMATCH")
                                                        .contains(code))
                                .toList());
        return JSON.writeValueAsString(body);
    }

    public static Map<String, Object> currentGoalReview(JsonNode request, boolean approved) {
        var result = new LinkedHashMap<String, Object>(logicReview(approved));
        result.put(
                "reviewed_micro_goal_id",
                request.path("current_goal_assessment").path("micro_goal_id").asString());
        result.put(
                "reviewed_learning_state",
                request.path("current_goal_assessment").path("proposed_learning_state").asString());
        return result;
    }

    public static Map<String, Object> scopedReviews(JsonNode request, boolean approved) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : request.path("observation_tasks").propertyNames()) {
            var task = request.path("observation_tasks").path(key);
            var legacy = JSON.createObjectNode();
            legacy.putObject("analysis_result")
                    .putObject("goal_observation")
                    .putArray("observations")
                    .add(task);
            legacy.set("learner_turn", request.path("learner_turn"));
            var row = new LinkedHashMap<>(reviews(legacy, approved).getFirst());
            row.remove("micro_goal_id");
            row.remove("criterion_reference");
            result.put(key, row);
        }
        return result;
    }

    public static JsonNode analysisForRequest(JsonNode request) {
        if (request.has("analysis_result")) return request.path("analysis_result");
        var analysis =
                (tools.jackson.databind.node.ObjectNode)
                        request.path("interpretation_strategy").deepCopy();
        analysis.set(
                "learning_state",
                request.path("current_goal_assessment").path("proposed_learning_state"));
        var goal =
                analysis.putObject("goal_observation").put("schema_version", "analysis-result/v2");
        goal.set("analysis_id", request.path("analysis_id"));
        var rows = goal.putArray("observations");
        for (var task : request.path("observation_tasks")) {
            var row = rows.addObject();
            row.set("reference", task.path("reference"));
            row.set("status", task.path("status"));
        }
        var activity = goal.putObject("activity_goal");
        activity.set("decision", request.path("activity_assessment").path("proposed_decision"));
        activity.set("basis", request.path("activity_assessment").path("basis"));
        return analysis;
    }

    public static Map<String, Object> logicReview(boolean approved) {
        return Map.of(
                "verdict",
                approved ? "SUPPORTED" : "UNSUPPORTED",
                "reason_code",
                approved ? "LOGIC_GROUNDED" : "LEARNING_STATE_MISMATCH",
                "explanation",
                "합성 분석 논리를 검수했다.");
    }

    public static Map<String, Object> activityReview(JsonNode request, boolean approved) {
        var goal = request.path("analysis_result").path("goal_observation").path("activity_goal");
        if (request.has("activity_assessment")) goal = request.path("activity_assessment");
        String decision =
                goal.path(request.has("activity_assessment") ? "proposed_decision" : "decision")
                        .asString();
        if (decision.isEmpty()) decision = "IN_PROGRESS";
        String reason =
                switch (decision) {
                    case "ACHIEVED" -> "ACHIEVEMENT_NOT_GROUNDED";
                    case "UNAVAILABLE" -> "UNAVAILABLE_NOT_GROUNDED";
                    default -> "CONTINUATION_NOT_GROUNDED";
                };
        return Map.of(
                "reviewed_decision",
                decision,
                "verdict",
                approved ? "SUPPORTED" : "UNSUPPORTED",
                "reason_code",
                approved ? "ASSESSMENT_GROUNDED" : reason,
                "evidence_references",
                goal.path("basis").isArray()
                        ? JSON.convertValue(goal.path("basis"), List.class)
                        : List.of(),
                "explanation",
                "합성 활동 판정을 검수했다.");
    }

    public static List<Map<String, Object>> reviews(JsonNode request, boolean approved) {
        var result = new ArrayList<Map<String, Object>>();
        for (var observation :
                request.path("analysis_result").path("goal_observation").path("observations")) {
            var ref = observation.path("reference");
            boolean absent = observation.path("status").asString().equals("NOT_EXPRESSED");
            var item = new LinkedHashMap<String, Object>();
            item.put("micro_goal_id", ref.path("micro_goal_id").asString());
            item.put("criterion_reference", ref.path("criterion_reference").asString());
            item.put("verdict", approved ? "SUPPORTED" : "UNSUPPORTED");
            item.put(
                    "reason_code",
                    approved
                            ? absent ? "ABSENCE_CONFIRMED" : "MATCHED_EXPRESSION"
                            : absent ? "STATUS_MISMATCH" : "NO_MATCHING_EXPRESSION");
            item.put(
                    "evidence_quote",
                    approved == absent
                            ? null
                            : request.path("learner_turn").path("canonical_utterance").asString());
            item.put("explanation", "합성 관찰 분류를 검수했다.");
            result.add(item);
        }
        return result;
    }
}
