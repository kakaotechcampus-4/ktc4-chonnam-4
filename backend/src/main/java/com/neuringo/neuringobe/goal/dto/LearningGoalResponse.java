package com.neuringo.neuringobe.goal.dto;

import com.neuringo.neuringobe.goal.domain.LearningGoal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LearningGoalResponse(
        UUID goalId,
        UUID childId,
        String instructorId,
        UUID parentGoalId,
        String title,
        String situationType,
        List<Map<String, Object>> characters,
        List<String> requiredElements,
        List<String> forbiddenExpressions,
        String contentHash,
        Instant createdAt) {
    public static LearningGoalResponse from(LearningGoal goal) {
        return new LearningGoalResponse(
                goal.getGoalId(),
                goal.getChildId(),
                goal.getInstructorId(),
                goal.getParentGoalId(),
                goal.getTitle(),
                goal.getSituationType(),
                goal.getCharacters(),
                goal.getRequiredElements(),
                goal.getForbiddenExpressions(),
                goal.getContentHash(),
                goal.getCreatedAt());
    }
}
