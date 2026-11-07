package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import java.util.List;
import java.util.Objects;

/** 프롬프트에 준 글자 수·항목 수 제한을 지킨다. 글자 수는 공백을 포함한 코드 포인트 수다. */
public final class LengthRule implements ReportQualityRule {

    private final ReportLimits limits;

    public LengthRule(ReportLimits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    @Override
    public List<ReportIssue> check(ReportDraft draft, LearningReportInput input) {
        boolean tooLong =
                length(draft.summary()) > limits.summaryMaxChars()
                        || draft.items()
                                .anyMatch(item -> length(item.text()) > limits.itemMaxChars())
                        || List.of(
                                        draft.observations(),
                                        draft.effectiveSupports(),
                                        draft.difficultPoints())
                                .stream()
                                .anyMatch(section -> section.size() > limits.itemsPerSectionMax());
        if (!tooLong) return List.of();
        String detail =
                "요약 %d자, 항목 %d자, 목록마다 %d개 이하"
                        .formatted(
                                limits.summaryMaxChars(),
                                limits.itemMaxChars(),
                                limits.itemsPerSectionMax());
        return List.of(new ReportIssue(ReportCodes.TOO_LONG, detail));
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
