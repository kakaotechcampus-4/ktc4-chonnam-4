package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import java.util.List;
import java.util.Objects;

/** 리포트 루프의 결과. Completed 만 강사 화면에 보인다(03 VS-014 "생성 중이거나 생성에 실패한 리포트는 강사 화면에 표시하지 않는다"). */
public sealed interface LearningReportResult
        permits LearningReportResult.Completed, LearningReportResult.Failed {

    List<ReportRound> rounds();

    record Completed(ReportDraft draft, ReportReview review, List<ReportRound> rounds)
            implements LearningReportResult {

        public Completed {
            Objects.requireNonNull(draft, "draft must not be null");
            Objects.requireNonNull(review, "review must not be null");
            rounds = List.copyOf(rounds);
        }
    }

    record Failed(FailureReason reason, AiOperation operation, List<ReportRound> rounds)
            implements LearningReportResult {

        public Failed {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            rounds = List.copyOf(rounds);
        }
    }

    enum FailureReason {
        /** 고쳐 쓰기를 정해진 바퀴만큼 했는데도 통과하지 못함 */
        QUALITY_LIMIT_REACHED,
        /** 재시도할 수 있는 호출 실패가 한도까지 이어짐 */
        CALL_LIMIT_REACHED,
        /** 인증 오류처럼 다시 불러도 소용없는 실패 */
        NON_RETRYABLE_FAILURE
    }
}
