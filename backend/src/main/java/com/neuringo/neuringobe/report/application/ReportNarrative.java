package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import java.util.List;

/**
 * 생성·평가 루프를 통과한 AI 초안의 항목. learning_report.items 에 두고 리포트 조회 응답의 narrative 로 돌려준다. 항목마다 근거 ID(E1…)를
 * 함께 둬서 화면이나 감사에서 어느 기록에 기댄 문장인지 확인할 수 있다.
 */
public record ReportNarrative(
        List<Item> observations, List<Item> effectiveSupports, List<Item> difficultPoints) {

    public static ReportNarrative from(ReportDraft draft) {
        return new ReportNarrative(
                items(draft.observations()),
                items(draft.effectiveSupports()),
                items(draft.difficultPoints()));
    }

    private static List<Item> items(List<ReportItem> source) {
        return source.stream().map(item -> new Item(item.text(), item.evidenceIds())).toList();
    }

    public record Item(String text, List<String> evidenceIds) {}
}
