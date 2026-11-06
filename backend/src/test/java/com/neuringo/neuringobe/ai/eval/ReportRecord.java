package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.report.ReportRound;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewScores;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import java.util.List;

/**
 * 회귀 사례 한 번의 실행 결과.
 *
 * @param failureReason 리포트가 실패했으면 LearningReportResult.FailureReason 이름, 통과했으면 null
 * @param formatFailureSamples 출력 검사에 걸린 호출(재시도로 넘어간 것 포함)마다 단계·이유·원문 앞부분
 * @param checkFailures 루프 규칙과 따로 본 기대값 위반. 통과한 리포트에서만 센다
 */
record ReportRecord(
        String caseId,
        String purpose,
        int repeat,
        boolean displayable,
        String failureReason,
        List<ReportRound> rounds,
        List<RecordingLlmProvider.Call> calls,
        List<String> formatFailureSamples,
        long wallMs,
        List<String> checkFailures,
        ReportDraft draft,
        ReportReviewScores finalScores,
        LearningMetrics metrics) {

    int formatFailures() {
        return formatFailureSamples.size();
    }

    boolean passedFirstRound() {
        return displayable && rounds.size() == 1;
    }

    int inputTokens() {
        return calls.stream().mapToInt(c -> c.inputTokens() == null ? 0 : c.inputTokens()).sum();
    }

    int outputTokens() {
        return calls.stream().mapToInt(c -> c.outputTokens() == null ? 0 : c.outputTokens()).sum();
    }
}
