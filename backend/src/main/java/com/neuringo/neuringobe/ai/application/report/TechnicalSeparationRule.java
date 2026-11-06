package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.EvidenceKind;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import java.util.List;
import java.util.Set;

/**
 * 기술 오류를 아동의 어려움으로 쓰지 않는다(03 VS-014 "STT·네트워크 오류를 아동의 어려움으로 표시하지 않는다"). 어려운 지점이 TECHNICAL 근거를 달거나,
 * 기술 오류 낱말을 쓰면 걸린다.
 */
public final class TechnicalSeparationRule implements ReportQualityRule {

    private static final List<String> TECHNICAL_TERMS =
            List.of("음성 인식", "인식 오류", "인식이 안", "마이크", "네트워크", "연결이 끊", "연결 문제", "STT");

    @Override
    public List<ReportIssue> check(ReportDraft draft, LearningReportInput input) {
        Set<String> technicalIds = input.evidenceIdsOf(EvidenceKind.TECHNICAL);
        for (ReportItem item : draft.difficultPoints()) {
            boolean citesTechnical = item.evidenceIds().stream().anyMatch(technicalIds::contains);
            boolean mentionsTechnical = TECHNICAL_TERMS.stream().anyMatch(item.text()::contains);
            if (citesTechnical || mentionsTechnical) {
                return List.of(
                        new ReportIssue(
                                ReportCodes.TECHNICAL_ERROR_AS_DIFFICULTY,
                                "어려운 지점에 기술 오류를 쓰지 않는다"));
            }
        }
        return List.of();
    }
}
