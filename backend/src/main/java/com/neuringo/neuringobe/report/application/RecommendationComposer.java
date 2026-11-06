package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.report.application.port.GoalSimilaritySearch;
import com.neuringo.neuringobe.report.application.port.SimilarGoal;
import com.neuringo.neuringobe.report.domain.RecommendationCandidate;
import com.neuringo.neuringobe.report.domain.RecommendationPolicy;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 추천 후보(RecommendationPolicy)에 확장 목표 후보를 붙인다(03 VS-015 "학습 기록 분석 → 추천 생성 → 이유·근거 충분성 표시").
 *
 * <p>확장 후보는 지금 목표와 뜻이 가깝지만 같은 목표는 아닌 것을 고른다. 유사도가 minSimilarity 보다 낮으면 관련이 적은 목표라서,
 * duplicateSimilarity 이상이면 표현만 다른 같은 목표라서 뺀다. 두 값은 운영값이고 임베딩 모델을 바꾸면 다시 맞춘다.
 *
 * <p>같은 목표 기준은 처음 0.95 였다. 2026-10-29 text-embedding-3-small 실측에서 "감정의 이유 말하기"와 "감정의 이유를 말해 보기"가
 * 0.949 로 나와 같은 목표가 다음 목표 후보로 골라져서 0.90 으로 낮췄다.
 */
public final class RecommendationComposer {

    public static final double DEFAULT_MIN_SIMILARITY = 0.5;
    public static final double DEFAULT_DUPLICATE_SIMILARITY = 0.90;
    private static final int SEARCH_LIMIT = 5;

    private final RecommendationPolicy policy;
    private final GoalSimilaritySearch search;
    private final double minSimilarity;
    private final double duplicateSimilarity;

    public RecommendationComposer(RecommendationPolicy policy, GoalSimilaritySearch search) {
        this(policy, search, DEFAULT_MIN_SIMILARITY, DEFAULT_DUPLICATE_SIMILARITY);
    }

    public RecommendationComposer(
            RecommendationPolicy policy,
            GoalSimilaritySearch search,
            double minSimilarity,
            double duplicateSimilarity) {
        this.policy = Objects.requireNonNull(policy);
        this.search = Objects.requireNonNull(search);
        if (minSimilarity >= duplicateSimilarity) {
            throw new IllegalArgumentException("minSimilarity must be below duplicateSimilarity");
        }
        this.minSimilarity = minSimilarity;
        this.duplicateSimilarity = duplicateSimilarity;
    }

    public List<Recommendation> compose(RecommendationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return policy.recommend(request.goals()).stream()
                .map(candidate -> new Recommendation(candidate, suggestion(candidate, request)))
                .toList();
    }

    private SimilarGoal suggestion(
            RecommendationCandidate candidate, RecommendationRequest request) {
        if (candidate.type() != RecommendationType.EXTEND) return null;
        Set<UUID> excluded = new HashSet<>(request.completedGoalIds());
        excluded.add(request.currentGoalId());
        return search
                .findSimilar(request.classId(), request.currentGoalText(), excluded, SEARCH_LIMIT)
                .stream()
                .filter(goal -> goal.similarity() >= minSimilarity)
                .filter(goal -> goal.similarity() < duplicateSimilarity)
                .findFirst()
                .orElse(null);
    }
}
