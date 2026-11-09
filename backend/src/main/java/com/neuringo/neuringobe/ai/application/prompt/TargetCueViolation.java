package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 거절할 때만 짧은 사유와 실제 대사 인용을 받는다. 단서의 의미는 독립 검수가 판단한다. */
record TargetCueViolation(String reason, String quote) {
    static final String CONFLICT = "목표 단서 거절의 사유·인용·판정이 일치하지 않습니다.";
    static final String POLICY = "S0_OBSERVABLE_CUE_V1";
    private static final Set<String> STRATEGIES =
            Set.of("PROBE_EVENT", "PROBE_EMOTION", "PROBE_CAUSE", "PROBE_ACTION", "ADVANCE_GOAL");

    static boolean learningStrategy(String strategy) {
        return STRATEGIES.contains(strategy);
    }

    static Map<String, Object> schema(AnalysisResult analysis, CharacterResponseContract contract) {
        if (!applicable(analysis, contract)) return Map.of("type", "null");
        return Map.of(
                "type",
                List.of("object", "null"),
                "properties",
                Map.of(
                        "reason",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of(
                                                "NO_TARGET_CUE",
                                                "WRONG_TARGET_CUE",
                                                "NO_REACTION_TO_INPUT")),
                        "quote", Map.of("type", "string", "minLength", 1)),
                "required",
                List.of("reason", "quote"),
                "additionalProperties",
                false);
    }

    static void requireValid(
            EvaluationOutput output,
            AnalysisResult analysis,
            CharacterResponseContract contract,
            CandidateResponse candidate) {
        if (contract == null) return;
        var violation = output.targetCueViolation();
        boolean priority =
                output.decision() == EvaluationDecision.SAFETY_ESCALATION
                        || output.decision() == EvaluationDecision.CONFIRM_INPUT;
        if (!applicable(analysis, contract) || priority) {
            if (violation != null) fail();
            return;
        }
        boolean code = output.failureCodes().contains("ANALYSIS_RESPONSE_MISMATCH");
        boolean rejected = Boolean.FALSE.equals(output.checks().analysisResponseConsistent());
        if (!code && !rejected) {
            if (violation != null) fail();
            return;
        }
        if (!code
                || !rejected
                || violation == null
                || violation.quote().isBlank()
                || !candidate.text().contains(violation.quote())) fail();
        if (violation.reason().equals("NO_TARGET_CUE")
                && !candidate.text().equals(violation.quote())) fail();
    }

    private static boolean applicable(AnalysisResult analysis, CharacterResponseContract contract) {
        return contract != null && learningStrategy(analysis.nextStrategy().type());
    }

    private static void fail() {
        throw new InvalidLlmOutputException(CONFLICT, null);
    }
}
