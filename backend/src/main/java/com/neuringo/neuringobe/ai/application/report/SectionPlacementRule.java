package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.Evidence;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput.GoalStatus;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 근거를 맞는 칸에 쓴다. 2026-10-29 실측에서 혼자 도달한 목표를 어려운 지점에, 열린 질문(S0)을 효과적인 지원에 쓴 초안이 평가를 통과했다.
 *
 * <ul>
 *   <li>어려운 지점: 단 근거가 모두 어려움 없이 도달한 목표(NO_SUPPORT_NEEDED·REACHED_AT_START)의 것이면 걸린다
 *   <li>효과적인 지원: 단 근거가 모두 지원 없이 도달한 목표(NO_SUPPORT_NEEDED)의 것이면 걸린다
 * </ul>
 *
 * <p>목표 상태가 없는 근거(퀴즈 결과 등)를 하나라도 달면 이 규칙은 판단하지 않는다.
 */
public final class SectionPlacementRule implements ReportQualityRule {

    @Override
    public List<ReportIssue> check(ReportDraft draft, LearningReportInput input) {
        boolean misplacedDifficulty =
                draft.difficultPoints().stream()
                        .anyMatch(
                                item ->
                                        onlyCites(
                                                item, input, GoalStatus::reachedWithoutDifficulty));
        boolean misplacedSupport =
                draft.effectiveSupports().stream()
                        .anyMatch(
                                item ->
                                        onlyCites(
                                                item,
                                                input,
                                                status -> status == GoalStatus.NO_SUPPORT_NEEDED));
        if (!misplacedDifficulty && !misplacedSupport) return List.of();
        String detail =
                misplacedDifficulty && misplacedSupport
                        ? "어려운 지점·효과적인 지원에 지원 없이 도달한 목표를 썼다"
                        : misplacedDifficulty
                                ? "어려운 지점에 어려움 없이 도달한 목표를 썼다"
                                : "효과적인 지원에 지원 없이 도달한 목표를 썼다";
        return List.of(new ReportIssue(ReportCodes.MISPLACED_ITEM, detail));
    }

    private static boolean onlyCites(
            ReportItem item, LearningReportInput input, Predicate<GoalStatus> status) {
        List<GoalStatus> statuses =
                item.evidenceIds().stream()
                        .map(input::find)
                        .filter(Objects::nonNull)
                        .map(Evidence::goalStatus)
                        .toList();
        return !statuses.isEmpty()
                && statuses.size() == item.evidenceIds().size()
                && statuses.stream().allMatch(s -> s != null && status.test(s));
    }
}
