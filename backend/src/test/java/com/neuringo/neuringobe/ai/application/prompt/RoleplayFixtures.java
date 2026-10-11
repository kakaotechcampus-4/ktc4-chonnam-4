package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
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
                 "analysis_confidence":0.9}
                """
                .formatted(TURN_ID, strategy, target, level);
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
        return """
                {"candidate_id":"%s",
                 "checks":{"format_valid":true,"safety_valid":true},
                 "failure_codes":%s,"critical_failure_count":%d,
                 "decision":"%s","retry_target":%s,"safe_to_send":%s,
                 "revision_instruction":%s}
                """
                .formatted(
                        CANDIDATE_ID,
                        failureCodes,
                        critical,
                        decision,
                        retryTarget == null ? "null" : "\"" + retryTarget + "\"",
                        safe,
                        revision);
    }
}
