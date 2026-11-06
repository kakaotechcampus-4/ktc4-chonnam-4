package com.neuringo.neuringobe.report.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 추천 운영값(03 VS-015 "추천 개수와 정렬은 운영값으로 둔다"). 유형마다 최대 한 개(DEC-013 제안 기본값)이고, order 순서로 보인다.
 *
 * @param extendAtLeastPercent 확장 추천을 낼 점수율. 후속 난이도의 상향 후보 기준과 같게 둔다.
 */
public record RecommendationRules(
        String version,
        List<RecommendationType> order,
        int sufficientValidGoals,
        int extendAtLeastPercent) {

    public static final RecommendationRules V1 =
            new RecommendationRules(
                    "RECOMMENDATION_V1",
                    List.of(
                            RecommendationType.REINFORCE,
                            RecommendationType.REPEAT,
                            RecommendationType.APPLY,
                            RecommendationType.EXTEND),
                    2,
                    80);

    public RecommendationRules {
        Objects.requireNonNull(version, "version must not be null");
        order = List.copyOf(order);
        if (order.size() != RecommendationType.values().length
                || !EnumSet.copyOf(order).equals(EnumSet.allOf(RecommendationType.class))) {
            throw new IllegalArgumentException("order must list every type once");
        }
        if (sufficientValidGoals < 1 || extendAtLeastPercent < 0 || extendAtLeastPercent > 100) {
            throw new IllegalArgumentException("rules are out of range");
        }
    }
}
