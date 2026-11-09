package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.UUID;

/** 지원 보정이 끝난 분석으로 생성과 검수의 허용 범위를 고정한다. */
record CharacterResponseContract(
        @JsonProperty("target_micro_goal_id") UUID targetMicroGoalId,
        @JsonProperty("applied_support_level") String appliedSupportLevel,
        @JsonProperty("expected_response_type") String expectedResponseType,
        @JsonProperty("target_answer_allowed") boolean targetAnswerAllowed,
        @JsonProperty("mascot_answer_allowed") boolean mascotAnswerAllowed,
        @JsonProperty("question_required") boolean questionRequired,
        @JsonProperty("learner_quiz_allowed") boolean learnerQuizAllowed,
        @JsonProperty("cue_policy") String cuePolicy) {
    static CharacterResponseContract from(AnalysisResult analysis) {
        var next = analysis.nextStrategy();
        String type = responseType(next.type(), next.recommendedSupportLevel());
        return new CharacterResponseContract(
                next.targetMicroGoalId(),
                next.recommendedSupportLevel(),
                type,
                "S3".equals(next.recommendedSupportLevel()),
                false,
                false,
                false,
                "S0".equals(next.recommendedSupportLevel())
                                && TargetCueViolation.learningStrategy(next.type())
                        ? TargetCueViolation.POLICY
                        : null);
    }

    static String responseType(String strategy, String support) {
        return switch (strategy) {
            case "PROBE_EVENT", "PROBE_EMOTION", "PROBE_CAUSE", "PROBE_ACTION", "ADVANCE_GOAL" ->
                    "S3".equals(support) ? "ANSWER_REVEAL" : "GUIDING_QUESTION";
            case "REDIRECT_TO_SCENARIO" -> "REDIRECT";
            case "CONFIRM_UNDERSTANDING" -> "CONFIRMATION";
            case "CLOSE_ROLEPLAY" -> "CLOSING";
            default -> throw new IllegalArgumentException("지원 계약을 만들 수 없는 전략입니다.");
        };
    }
}
