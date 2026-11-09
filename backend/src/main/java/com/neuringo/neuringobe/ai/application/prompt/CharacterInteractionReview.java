package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import java.util.List;
import java.util.Map;

/** 발화 방식의 의미는 독립 검수가 판단하고, 서버는 실제 인용과 거절의 일관성을 확인한다. */
record CharacterInteractionReview(String mode, String quote) {
    static final String QUESTION_CONFLICT = "캐릭터 대사는 질문 부재만으로 거절할 수 없습니다.";
    static final String CONFLICT = "캐릭터 상호작용 검수의 인용 또는 판정이 일치하지 않습니다.";

    static Map<String, Object> schema(CharacterResponseContract contract) {
        if (contract == null) return Map.of("type", "null");
        return Map.of(
                "type", List.of("object", "null"),
                "properties",
                        Map.of(
                                "mode",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of(
                                                        "CHARACTER_REACTION",
                                                        "CHARACTER_QUESTION",
                                                        "LEARNER_QUIZ",
                                                        "ROLE_REVERSAL")),
                                "quote", Map.of("type", "string", "minLength", 1)),
                "required", List.of("mode", "quote"),
                "additionalProperties", false);
    }

    static void requireValid(
            EvaluationOutput output,
            CharacterResponseContract contract,
            CandidateResponse candidate) {
        if (contract == null) return;
        if (output.failureCodes().contains("MISSING_QUESTION"))
            throw new InvalidLlmOutputException(QUESTION_CONFLICT, null);
        var review = output.characterInteractionReview();
        boolean priority =
                output.decision() == EvaluationDecision.SAFETY_ESCALATION
                        || output.decision() == EvaluationDecision.CONFIRM_INPUT;
        if (priority && review == null) return;
        if (review == null
                || review.quote().isBlank()
                || !candidate.text().contains(review.quote())) fail();
        if ((review.mode().equals("LEARNER_QUIZ") || review.mode().equals("ROLE_REVERSAL"))
                && (!output.failureCodes().contains("CHARACTER_BREAK")
                        || !Boolean.FALSE.equals(output.checks().conversationCoherent()))) fail();
    }

    private static void fail() {
        throw new InvalidLlmOutputException(CONFLICT, null);
    }
}
