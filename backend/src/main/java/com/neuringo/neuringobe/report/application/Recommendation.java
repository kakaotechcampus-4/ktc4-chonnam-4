package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.report.application.port.SimilarGoal;
import com.neuringo.neuringobe.report.domain.RecommendationCandidate;
import java.util.Objects;

/**
 * @param suggestedGoal 확장 추천에서 찾은 다음 목표 후보. 다른 유형이거나 맞는 목표가 없으면 null 이고 강사가 직접 고른다.
 */
public record Recommendation(RecommendationCandidate candidate, SimilarGoal suggestedGoal) {

    public Recommendation {
        Objects.requireNonNull(candidate, "candidate must not be null");
    }
}
