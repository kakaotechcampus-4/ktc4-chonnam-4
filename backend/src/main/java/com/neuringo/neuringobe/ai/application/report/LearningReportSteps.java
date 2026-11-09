package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.ReportRevision;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;

/** 리포트 루프가 부르는 두 호출. 호출 한 번은 한 번만 시도하고, 재시도는 RetryingLearningReportGenerator 가 정한다. */
public interface LearningReportSteps {

    /**
     * @param revision 첫 초안이면 null
     * @param stageAttempt 이 단계(초안 작성)를 몇 번째 부르는지. 1 부터 센다.
     */
    AiCallResult<ReportDraft> draft(
            LearningReportInput input,
            ReportRevision revision,
            AiAttemptContext attempts,
            int stageAttempt);

    AiCallResult<ReportReview> review(
            LearningReportInput input,
            ReportDraft draft,
            AiAttemptContext attempts,
            int stageAttempt);
}
