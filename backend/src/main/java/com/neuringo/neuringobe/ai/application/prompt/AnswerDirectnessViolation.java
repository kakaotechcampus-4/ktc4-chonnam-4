package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 직접성 거절의 위치와 인용을 확인하며, 인용의 교육적 의미는 검수 모델이 판단한다. */
record AnswerDirectnessViolation(
        String reason, @JsonProperty("speaker_role") String speakerRole, String quote) {
    static final String CONFLICT = "직접성 거절 근거가 지원 계약 또는 후보와 충돌합니다.";

    static Map<String, Object> schema(
            CharacterResponseContract contract, CandidateResponse candidate) {
        var reasons = new java.util.ArrayList<String>();
        if (contract == null || !contract.targetAnswerAllowed())
            reasons.add("TARGET_ANSWER_BEFORE_S3");
        if (candidate == null || candidate.mascotHint() != null)
            reasons.add("MASCOT_ANSWER_REVEAL");
        if (reasons.isEmpty()) return Map.of("type", "null");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("reason", Map.of("type", "string", "enum", reasons));
        properties.put(
                "speaker_role", Map.of("type", "string", "enum", List.of("DIALOGUE", "HINT")));
        properties.put("quote", Map.of("type", "string", "minLength", 1));
        return Map.of(
                "type",
                List.of("object", "null"),
                "properties",
                properties,
                "required",
                List.copyOf(properties.keySet()),
                "additionalProperties",
                false);
    }

    static void requireValid(
            EvaluationOutput output,
            CharacterResponseContract contract,
            CandidateResponse candidate) {
        if (contract == null) return;
        var violation = output.answerDirectnessViolation();
        boolean rejected = output.failureCodes().contains("ANSWER_TOO_DIRECT");
        boolean priority =
                output.decision() == EvaluationDecision.SAFETY_ESCALATION
                        || output.decision() == EvaluationDecision.CONFIRM_INPUT;
        if (priority && violation == null) return;
        if (!rejected) {
            if (violation != null) fail();
            return;
        }
        if (violation == null || !Boolean.FALSE.equals(output.checks().difficultyAppropriate()))
            fail();
        String source;
        switch (violation.reason()) {
            case "TARGET_ANSWER_BEFORE_S3" -> {
                if (contract.targetAnswerAllowed() || !"DIALOGUE".equals(violation.speakerRole()))
                    fail();
                source = candidate.text();
            }
            case "MASCOT_ANSWER_REVEAL" -> {
                if (!"HINT".equals(violation.speakerRole())) fail();
                source = candidate.mascotHint();
            }
            default -> throw new InvalidLlmOutputException(CONFLICT, null);
        }
        if (source == null || violation.quote().isBlank() || !source.contains(violation.quote()))
            fail();
    }

    private static void fail() {
        throw new InvalidLlmOutputException(CONFLICT, null);
    }
}
