package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 진단·성향 단정 표현을 쓰지 않는다(03 VS-014·VS-015). 낱말 목록으로 확실한 것만 막고, 문맥이 필요한 단정은 평가 모델의 non_diagnostic 점수가
 * 본다.
 */
public final class DiagnosticLanguageRule implements ReportQualityRule {

    private static final List<String> FORBIDDEN_TERMS =
            List.of(
                    "진단", "장애", "자폐", "adhd", "발달 지연", "발달지연", "지적 능력", "지능", "문제아", "산만", "공격적",
                    "증상", "결함", "성향");

    @Override
    public List<ReportIssue> check(ReportDraft draft, LearningReportInput input) {
        Set<String> found = new TreeSet<>();
        Stream.concat(Stream.of(draft.summary()), draft.items().map(ReportItem::text))
                .map(text -> text.toLowerCase(Locale.ROOT))
                .forEach(
                        text ->
                                FORBIDDEN_TERMS.stream()
                                        .filter(text::contains)
                                        .forEach(found::add));
        if (found.isEmpty()) return List.of();
        return List.of(
                new ReportIssue(
                        ReportCodes.DIAGNOSTIC_LANGUAGE, "쓰지 않을 낱말: " + String.join(", ", found)));
    }
}
