package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import java.util.List;

/** 기본 규칙 묶음. 싼 검사부터 둔다. */
public final class ReportQualityRules {

    private ReportQualityRules() {}

    public static List<ReportQualityRule> defaults(ReportLimits limits) {
        return List.of(
                new EvidenceReferenceRule(),
                new TechnicalSeparationRule(),
                new SectionPlacementRule(),
                new DiagnosticLanguageRule(),
                new LengthRule(limits));
    }
}
