package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import java.util.List;

/**
 * 평가 모델을 부르기 전에 코드로 확인할 수 있는 리포트 규칙 하나. 걸리면 평가 호출 없이 바로 고쳐 쓰게 한다. 새 규칙은 이 인터페이스를 구현해서
 * RetryingLearningReportGenerator 에 넘기면 되고, 루프 코드는 바꾸지 않는다.
 */
@FunctionalInterface
public interface ReportQualityRule {

    List<ReportIssue> check(ReportDraft draft, LearningReportInput input);
}
