package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.report.domain.GoalEvidence;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 추천 요청. currentGoalText 는 지금 학습 목표의 제목·설명이고 확장 후보를 찾는 질의가 된다.
 *
 * @param completedGoalIds 이 아동이 이미 마친 목표. 확장 후보에서 뺀다.
 */
public record RecommendationRequest(
        UUID classId,
        UUID currentGoalId,
        String currentGoalText,
        Set<UUID> completedGoalIds,
        List<GoalEvidence> goals) {

    public RecommendationRequest {
        Objects.requireNonNull(classId, "classId must not be null");
        Objects.requireNonNull(currentGoalId, "currentGoalId must not be null");
        Objects.requireNonNull(currentGoalText, "currentGoalText must not be null");
        completedGoalIds = completedGoalIds == null ? Set.of() : Set.copyOf(completedGoalIds);
        goals = goals == null ? List.of() : List.copyOf(goals);
    }
}
