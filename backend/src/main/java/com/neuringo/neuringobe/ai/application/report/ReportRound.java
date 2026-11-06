package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewScores;
import java.util.List;
import java.util.Objects;

/**
 * 루프 한 바퀴의 기록. 초안 문장은 담지 않고 결과·코드·점수만 담아서 운영 지표와 회귀 측정에 쓴다.
 *
 * @param scores 평가 모델까지 갔으면 그 점수, 아니면 null
 */
public record ReportRound(
        int round, Outcome outcome, List<String> failureCodes, ReportReviewScores scores) {

    public enum Outcome {
        /** 초안이나 평가 호출이 실패해 이 바퀴에서 멈춤 */
        CALL_FAILED,
        /** 규칙 검사에서 걸려 평가 호출 없이 다시 씀 */
        RULES_REJECTED,
        /** 평가 모델이 돌려보냄(또는 PASS 인데 기준 점수 미만) */
        REVIEW_REJECTED,
        PASSED
    }

    public ReportRound {
        Objects.requireNonNull(outcome, "outcome must not be null");
        failureCodes = failureCodes == null ? List.of() : List.copyOf(failureCodes);
        if (round < 1) throw new IllegalArgumentException("round starts at 1");
    }
}
