package com.neuringo.neuringobe.activity.dto;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record ActivityResponse(
        UUID activityId,
        UUID childId,
        UUID goalId,
        UUID scenarioId,
        String scenarioSource,
        String initialSupportLevel,
        boolean difficultyFallbackApplied,
        ActivityStatus status,
        OffsetDateTime assignedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime rewardIssuedAt) {

    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(
                activity.getActivityId(),
                activity.getChildId(),
                activity.getGoalId(),
                activity.getScenarioId(),
                activity.getScenarioSource(),
                activity.getInitialSupportLevel(),
                activity.isDifficultyFallbackApplied(),
                activity.getStatus(),
                koreanTime(activity.getAssignedAt()),
                koreanTime(activity.getStartedAt()),
                koreanTime(activity.getCompletedAt()),
                koreanTime(activity.getRewardIssuedAt()));
    }

    private static OffsetDateTime koreanTime(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.ofHours(9));
    }
}
