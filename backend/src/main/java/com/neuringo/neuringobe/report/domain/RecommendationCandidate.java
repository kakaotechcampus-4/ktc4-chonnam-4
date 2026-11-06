package com.neuringo.neuringobe.report.domain;

import java.util.List;
import java.util.Objects;

/**
 * 강사가 검토할 추천 후보 하나. 강사가 확정하기 전에는 다음 활동을 만들지 않는다(03 VS-015).
 *
 * @param basis 추천의 바탕이 된 목표. 활동 전체를 보고 낸 추천(확장·근거 부족)이면 null.
 * @param evidence 화면에 함께 보일 관찰 증거
 */
public record RecommendationCandidate(
        RecommendationType type,
        Reason reason,
        GoalEvidence basis,
        List<String> evidence,
        EvidenceSufficiency sufficiency) {

    public enum Reason {
        GOAL_NOT_REACHED,
        GOAL_NEEDED_ANSWER,
        REACHED_WITH_EXTRA_SUPPORT,
        REACHED_WITHOUT_EXTRA_SUPPORT,
        ALL_GOALS_WITHOUT_EXTRA_SUPPORT,
        NOT_ENOUGH_EVIDENCE
    }

    public RecommendationCandidate {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(sufficiency, "sufficiency must not be null");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
