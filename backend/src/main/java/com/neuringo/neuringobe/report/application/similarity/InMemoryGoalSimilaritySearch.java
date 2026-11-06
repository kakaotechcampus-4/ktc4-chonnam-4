package com.neuringo.neuringobe.report.application.similarity;

import com.neuringo.neuringobe.report.application.port.GoalSimilaritySearch;
import com.neuringo.neuringobe.report.application.port.SimilarGoal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 학급별 목표 벡터를 메모리에 두고 코사인 유사도로 찾는다. 개발·테스트용이고, 목표 수가 적은 MVP 에서는 운영에도 쓸 수 있다. 재시작하면 색인을 다시 만들어야 한다.
 */
public final class InMemoryGoalSimilaritySearch implements GoalSimilaritySearch {

    private final TextEmbedder embedder;
    private final Map<UUID, Map<UUID, IndexedGoal>> goalsByClass = new ConcurrentHashMap<>();

    public InMemoryGoalSimilaritySearch(TextEmbedder embedder) {
        this.embedder = Objects.requireNonNull(embedder);
    }

    /** 같은 목표를 다시 넣으면 바꾼다(목표 문장이 수정된 경우). */
    public void index(UUID classId, UUID goalId, String title) {
        Objects.requireNonNull(classId, "classId must not be null");
        Objects.requireNonNull(goalId, "goalId must not be null");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        goalsByClass
                .computeIfAbsent(classId, ignored -> new ConcurrentHashMap<>())
                .put(goalId, new IndexedGoal(goalId, title, embedder.embed(title)));
    }

    public void remove(UUID classId, UUID goalId) {
        Map<UUID, IndexedGoal> goals = goalsByClass.get(classId);
        if (goals != null) goals.remove(goalId);
    }

    @Override
    public List<SimilarGoal> findSimilar(
            UUID classId, String queryText, Set<UUID> excludedGoalIds, int limit) {
        Objects.requireNonNull(classId, "classId must not be null");
        Objects.requireNonNull(excludedGoalIds, "excludedGoalIds must not be null");
        if (queryText == null || queryText.isBlank() || limit < 1) return List.of();
        Map<UUID, IndexedGoal> goals = goalsByClass.getOrDefault(classId, Map.of());
        if (goals.isEmpty()) return List.of();
        float[] query = embedder.embed(queryText);
        return goals.values().stream()
                .filter(goal -> !excludedGoalIds.contains(goal.goalId()))
                .map(
                        goal ->
                                new SimilarGoal(
                                        goal.goalId(),
                                        goal.title(),
                                        CosineSimilarity.between(query, goal.vector())))
                .sorted(
                        Comparator.comparingDouble(SimilarGoal::similarity)
                                .reversed()
                                .thenComparing(SimilarGoal::title))
                .limit(limit)
                .toList();
    }

    private record IndexedGoal(UUID goalId, String title, float[] vector) {}
}
