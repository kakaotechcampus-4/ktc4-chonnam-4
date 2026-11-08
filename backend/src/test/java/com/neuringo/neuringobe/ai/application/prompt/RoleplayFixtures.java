package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.List;
import java.util.UUID;

/** 워크플로우 v3 17장 예시(넘어져서 우는 친구)를 그대로 옮긴 테스트 입력. */
final class RoleplayFixtures {

    static final UUID MG_EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID MG_EMOTION = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID MG_LINK = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    static final UUID TURN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID ANALYSIS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID CANDIDATE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e4");

    private RoleplayFixtures() {}

    static String bindAnalysis(String content, UUID analysisId) {
        return analysisId == null
                ? content
                : content.replace(ANALYSIS_ID.toString(), analysisId.toString());
    }

    static GoalObservationOutput.Review review(UUID analysisId) {
        return new GoalObservationOutput.Review(
                GoalObservationOutput.EVALUATION_VERSION, analysisId, true, true);
    }

    static RoleplayTurnInput input(String utterance) {
        return new RoleplayTurnInput(
                new RoleplayTurnInput.Scenario(
                        "감정을 이해하고 공감하기",
                        "L1",
                        List.of("두 친구가 사탕을 가지려고 했다.", "친구가 넘어졌다.", "친구가 울었다."),
                        List.of("친구가 일부러 넘어졌다고 단정하지 않는다."),
                        List.of(
                                new RoleplayTurnInput.MicroGoal(
                                        MG_EVENT,
                                        "친구가 넘어졌다는 사건을 파악한다.",
                                        List.of("넘어짐 또는 다침을 언급한다."),
                                        "ACHIEVED"),
                                new RoleplayTurnInput.MicroGoal(
                                        MG_EMOTION,
                                        "친구가 아프거나 속상할 수 있음을 파악한다.",
                                        List.of("상황에 맞는 감정을 표현한다."),
                                        "IN_PROGRESS"),
                                new RoleplayTurnInput.MicroGoal(
                                        MG_LINK,
                                        "감정과 넘어진 사건을 연결한다.",
                                        List.of("넘어졌기 때문에 아프거나 속상하다고 표현한다."),
                                        "NOT_OBSERVED"))),
                new RoleplayTurnInput.State(MG_EMOTION, "S1", 1, 0, false, 2, 6, 4),
                new RoleplayTurnInput.LearnerTurn(TURN_ID, utterance),
                List.of(
                        new RoleplayTurnInput.DialogueLine(
                                RoleplayTurnInput.Speaker.AI, "친구가 넘어져서 울고 있어. 친구는 어떤 기분일까?")));
    }

    /** AI 가 넘어진 친구를 1인칭으로 연기하는 같은 턴. */
    static RoleplayTurnInput characterInput(String utterance) {
        RoleplayTurnInput base = input(utterance);
        RoleplayTurnInput.Scenario scenario = base.scenario();
        return new RoleplayTurnInput(
                new RoleplayTurnInput.Scenario(
                        scenario.learningGoal(),
                        scenario.scenarioLevel(),
                        scenario.scenarioFacts(),
                        scenario.prohibitedInferences(),
                        scenario.microGoals(),
                        new RoleplayTurnInput.AiCharacter(
                                "FRIEND", "넘어져서 우는 친구", "소심하고 잘 우는 편", "반말, 짧은 문장")),
                base.state(),
                base.learnerTurn(),
                base.recentDialogue());
    }

    static AiTraceContext trace(UUID analysisId, UUID candidateId) {
        return new AiTraceContext(
                REQUEST_ID,
                ACTIVITY_ID,
                UUID.randomUUID(),
                CHILD_ID,
                UUID.randomUUID(),
                1,
                SESSION_ID,
                TURN_ID,
                analysisId,
                candidateId);
    }

    static AiAttemptContext attempts() {
        return new AiAttemptContext(0, 0, 0);
    }

    static String analysisJson(String strategy, UUID target, String level) {
        return """
                {"turn_id":"%s",
                 "analysis_basis":["아동은 '친구가 울고 있어'라고 말했다."],
                 "response_act":"ANSWER_ATTEMPT","relevance":"PARTIALLY_RELEVANT",
                 "learning_state":"PARTIAL_UNDERSTANDING",
                 "primary_gap":{"code":"MISSING_EMOTION","description":"감정을 말하지 않았다."},
                 "next_strategy":{"type":"%s","target_micro_goal_id":"%s",
                   "purpose":"감정을 말하게 돕는다.","recommended_support_level":"%s"},
                 "analysis_confidence":0.9,
                 "goal_observation":{"schema_version":"analysis-result/v2","analysis_id":"%s",
                   "observations":[{"reference":{"turn_id":"%s","micro_goal_id":"%s","criterion_reference":"test-evidence:%s:0"},"status":"NOT_EXPRESSED"}],
                   "activity_goal":{"decision":"IN_PROGRESS","basis":[]}}}
                """
                .formatted(TURN_ID, strategy, target, level, ANALYSIS_ID, TURN_ID, target, target);
    }

    static String candidateJson(String text, String responseType) {
        return """
                {"candidate_id":"%s","turn_id":"%s","text":"%s","response_type":"%s",
                 "strategy_used":"PROBE_EMOTION","target_micro_goal_id":"%s",
                 "support_level":"S1","previous_failed_candidate_ids":[]}
                """
                .formatted(CANDIDATE_ID, TURN_ID, text, responseType, MG_EMOTION);
    }

    static String evaluationJson(
            String decision,
            String retryTarget,
            boolean safe,
            int critical,
            String failureCodes,
            String revision) {
        return evaluationOutputJson(decision, failureCodes, revision);
    }

    static java.util.Map<String, Boolean> allChecks() {
        var checks = new java.util.LinkedHashMap<String, Boolean>();
        for (String key :
                List.of(
                        "format_valid",
                        "safety_valid",
                        "privacy_valid",
                        "scenario_consistent",
                        "analysis_supported",
                        "analysis_response_consistent",
                        "goal_aligned",
                        "educationally_correct",
                        "difficulty_appropriate",
                        "conversation_coherent",
                        "answerable",
                        "non_repetitive")) checks.put(key, true);
        return checks;
    }

    static java.util.Map<String, Boolean> evaluationChecks(List<String> codes) {
        var checks = allChecks();
        if (codes.contains("GOAL_SKIPPED")) checks.put("goal_aligned", false);
        if (codes.contains("UNSUPPORTED_CAUSE_ANALYSIS")) checks.put("analysis_supported", false);
        if (codes.contains("ANALYSIS_RESPONSE_MISMATCH")) {
            checks.put("analysis_response_consistent", false);
        }
        return checks;
    }

    static String recordedCharacterEvaluation(int index) throws Exception {
        try (var stream =
                RoleplayFixtures.class.getResourceAsStream(
                        "/ai/mock/roleplay-character-evaluation-replay.json")) {
            return tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readTree(
                            new String(
                                    java.util.Objects.requireNonNull(stream).readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8))
                    .get(index)
                    .path("content")
                    .asString();
        }
    }

    static String evaluationOutputJson(String decision, String codes, String revision) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("candidate_id", CANDIDATE_ID);
        var checks = evaluationChecks(json.readValue(codes, List.class));
        boolean logic = !Boolean.FALSE.equals(checks.remove("analysis_supported"));
        body.put("checks", checks);
        body.put("analysis_logic_review", EvaluationWireFixtures.logicReview(logic));
        body.put("decision", decision);
        body.put(
                "failure_codes",
                ((List<String>) json.readValue(codes, List.class))
                        .stream()
                                .filter(
                                        code ->
                                                !List.of(
                                                                "UNSUPPORTED_CAUSE_ANALYSIS",
                                                                "STATE_STRATEGY_MISMATCH")
                                                        .contains(code))
                                .toList());
        body.put("revision_instruction", json.readTree(revision));
        body.put(
                "goal_observation_review",
                java.util.Map.of(
                        "analysis_id",
                        ANALYSIS_ID,
                        "activity_review",
                        EvaluationWireFixtures.activityReview(json.createObjectNode(), true)));
        var observation = new java.util.LinkedHashMap<String, Object>();
        observation.put("micro_goal_id", MG_EMOTION);
        observation.put("criterion_reference", "test-evidence:" + MG_EMOTION + ":0");
        observation.put("verdict", "SUPPORTED");
        observation.put("reason_code", "ABSENCE_CONFIRMED");
        observation.put("evidence_quote", null);
        observation.put("explanation", "감정 표현이 없다.");
        body.put("observation_reviews", java.util.List.of(observation));
        var request = json.createObjectNode().put("analysis_id", ANALYSIS_ID.toString());
        request.putObject("current_goal_assessment")
                .put("micro_goal_id", MG_EMOTION.toString())
                .put("proposed_learning_state", "PARTIAL_UNDERSTANDING");
        request.putObject("activity_assessment")
                .put("proposed_decision", "IN_PROGRESS")
                .putArray("basis");
        var task =
                request.putObject("observation_tasks")
                        .putObject("o0")
                        .put("status", "NOT_EXPRESSED");
        task.putObject("reference")
                .put("micro_goal_id", MG_EMOTION.toString())
                .put("criterion_reference", "test-evidence:" + MG_EMOTION + ":0");
        request.putObject("learner_turn").put("canonical_utterance", "친구가 울고 있어");
        return EvaluationWireFixtures.forRequest(json.writeValueAsString(body), request);
    }
}
