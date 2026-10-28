package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.List;

/**
 * 강사 입력 하나를 한 번 평가한 결과.
 *
 * @param delivered 최대 시도 안에 계약 검사를 통과한 시나리오가 나왔는가
 * @param firstAttemptPass 첫 시도에서 바로 통과했는가
 * @param callWallMs 호출마다 걸린 시간
 * @param formatFailures 계약 검사에서 실패한 시도. content 는 모델 출력 원문(합성 입력으로 만든 것)이다
 */
record ScenarioRecord(
        String caseId,
        String scenarioLevel,
        int repeat,
        String model,
        boolean delivered,
        boolean firstAttemptPass,
        int calls,
        long totalMs,
        List<Long> callWallMs,
        int inputTokens,
        int outputTokens,
        String providerFailure,
        List<FormatFailure> formatFailures,
        GeneratedScenario scenario,
        List<ScenarioRegressionSet.Check> checks) {

    record FormatFailure(int attempt, String reason, String content) {}

    long passedChecks() {
        return checks.stream().filter(ScenarioRegressionSet.Check::passed).count();
    }
}
