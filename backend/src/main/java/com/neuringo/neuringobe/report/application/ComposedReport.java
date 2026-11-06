package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.ai.application.report.LearningReportResult;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import java.util.Objects;

/** 지표는 AI 결과와 상관없이 언제나 있다. 강사 화면에는 result 가 Completed 일 때만 보인다. */
public record ComposedReport(LearningMetrics metrics, LearningReportResult result) {

    public ComposedReport {
        Objects.requireNonNull(metrics, "metrics must not be null");
        Objects.requireNonNull(result, "result must not be null");
    }

    public boolean displayable() {
        return result instanceof LearningReportResult.Completed;
    }
}
