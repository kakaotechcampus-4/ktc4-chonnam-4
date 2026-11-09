package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import java.util.List;
import java.util.Map;

/** 질문 초점 변경과 실제 달성·마무리 주장을 구분한다. 후보 인용의 의미는 독립 검수 대상이다. */
record GoalProgressViolation(
        String reason, @JsonProperty("claim_source") String claimSource, String quote) {
    static final String CONFLICT = "목표 진행 거절이 실제 달성·마무리 주장과 일치하지 않습니다.";

    static Map<String, Object> schema() {
        return Map.of(
                "type", List.of("object", "null"),
                "properties",
                        Map.of(
                                "reason",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of(
                                                        "UNSUPPORTED_ACHIEVEMENT_CLAIM",
                                                        "PREMATURE_CLOSING")),
                                "claim_source",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of("ANALYSIS", "CANDIDATE")),
                                "quote", Map.of("type", List.of("string", "null"))),
                "required", List.of("reason", "claim_source", "quote"),
                "additionalProperties", false);
    }

    static void requireValid(
            EvaluationOutput output, AnalysisResult analysis, CandidateResponse candidate) {
        boolean priority =
                output.decision() == EvaluationDecision.CONFIRM_INPUT
                        || output.decision() == EvaluationDecision.SAFETY_ESCALATION;
        var violation = output.goalProgressViolation();
        if (priority && violation == null) return;
        if (!output.failureCodes().contains("GOAL_SKIPPED")) {
            if (violation != null) fail();
            return;
        }
        if (violation == null || !Boolean.FALSE.equals(output.checks().goalAligned())) fail();
        boolean closing = analysis.nextStrategy().type().equals("CLOSE_ROLEPLAY");
        if ("PREMATURE_CLOSING".equals(violation.reason())
                && "ANALYSIS".equals(violation.claimSource())
                && !closing) fail();
        if ("ANALYSIS".equals(violation.claimSource())) {
            if (violation.quote() != null) fail();
            if ("UNSUPPORTED_ACHIEVEMENT_CLAIM".equals(violation.reason())
                    && !analysis.learningState().equals("GOAL_ACHIEVED")
                    && analysis.goalObservation().activityGoal().decision()
                            != com.neuringo.neuringobe.ai.application.structured.output
                                    .GoalObservationOutput.ActivityDecision.ACHIEVED) fail();
        } else if ("CANDIDATE".equals(violation.claimSource())) {
            if (violation.quote() == null
                    || violation.quote().isBlank()
                    || !candidate.text().contains(violation.quote())) fail();
        } else fail();
    }

    private static void fail() {
        throw new InvalidLlmOutputException(CONFLICT, null);
    }
}
