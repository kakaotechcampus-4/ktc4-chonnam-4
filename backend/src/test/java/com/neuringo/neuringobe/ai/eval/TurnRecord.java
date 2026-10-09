package com.neuringo.neuringobe.ai.eval;

import java.util.List;

/**
 * 회귀 세트 한 턴을 한 번 실행한 결과. turns.jsonl 한 줄이 된다.
 *
 * <p>outcome: DELIVERED(전달 가능한 후보가 나옴), RECOVERY(안전 기본 응답·재입력 등 복구 경로), TIMED_OUT(턴 예산 초과).
 * firstEvaluationPass 는 첫 후보가 첫 응답 판단에서 바로 PASS 였는지다(재생성 없이 통과).
 */
record TurnRecord(
        String caseId,
        String suite,
        int repeat,
        String model,
        String outcome,
        String recovery,
        boolean firstEvaluationPass,
        long totalMs,
        int llmCalls,
        int inputTokens,
        int outputTokens,
        List<StageCall> calls,
        List<String> evaluationDecisions,
        List<String> failureCodes,
        List<FormatFailureSummary> formatFailures,
        Analysis analysis,
        Response response,
        List<Check> checks) {

    TurnRecord {
        calls = List.copyOf(calls);
        evaluationDecisions = List.copyOf(evaluationDecisions);
        failureCodes = List.copyOf(failureCodes);
        formatFailures = List.copyOf(formatFailures);
        checks = List.copyOf(checks);
    }

    boolean delivered() {
        return "DELIVERED".equals(outcome);
    }

    /** 원문 없이 단계·지연·토큰만 남긴 호출 기록. */
    record StageCall(
            String operation,
            int attempt,
            long wallMs,
            Integer inputTokens,
            Integer outputTokens,
            String finishReason,
            String providerFailure) {}

    /** 형식 실패 원문은 format-failures.jsonl 에 따로 쓴다. 여기에는 단계와 이유만 둔다. */
    record FormatFailureSummary(String operation, String reason) {}

    record Analysis(
            String responseAct,
            String relevance,
            String learningState,
            String primaryGap,
            String strategyType,
            String supportLevel,
            Double confidence) {}

    record Response(String text, String responseType, int length) {}

    /** 기대값 비교 한 항목. status 는 PASS·FAIL·NOT_EVALUATED(분석이나 응답이 없어서 비교 못 함). */
    record Check(String name, String status, String expected, String actual) {

        boolean passed() {
            return "PASS".equals(status);
        }

        boolean failed() {
            return "FAIL".equals(status);
        }
    }
}
