package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 모든 항목이 입력에 있는 근거 ID 를 하나 이상 단다(03 VS-014 "관찰 근거와 함께 표시된다"). */
public final class EvidenceReferenceRule implements ReportQualityRule {

    @Override
    public List<ReportIssue> check(ReportDraft draft, LearningReportInput input) {
        Set<String> known = input.evidenceIds();
        List<ReportIssue> issues = new ArrayList<>();
        Set<String> unknown = new TreeSet<>();
        boolean missing = false;
        for (ReportItem item : draft.items().toList()) {
            if (item.evidenceIds().isEmpty()) missing = true;
            for (String id : item.evidenceIds()) {
                if (!known.contains(id)) unknown.add(id);
            }
        }
        if (missing) {
            issues.add(new ReportIssue(ReportCodes.MISSING_EVIDENCE, "근거 ID 가 빈 항목이 있다"));
        }
        if (!unknown.isEmpty()) {
            issues.add(
                    new ReportIssue(
                            ReportCodes.UNKNOWN_EVIDENCE,
                            "없는 근거 ID: " + String.join(", ", unknown)));
        }
        return issues;
    }
}
