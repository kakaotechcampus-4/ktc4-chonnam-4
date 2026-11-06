package com.neuringo.neuringobe.assignment.dto;

import com.neuringo.neuringobe.activity.domain.ActivityBlockedReason;
import com.neuringo.neuringobe.activity.domain.ActivityNextAction;
import com.neuringo.neuringobe.activity.domain.ActivityReadiness;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 생성 응답은 유지하고 목록·상세에만 계산한 준비 상태를 포함한다. */
public record ActivityReadResponse(
        UUID activityId,
        UUID childId,
        UUID goalId,
        ActivityType activityType,
        UUID scenarioId,
        String scenarioSource,
        String initialSupportLevel,
        boolean difficultyFallbackApplied,
        ActivityStatus status,
        OffsetDateTime assignedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime rewardIssuedAt,
        ActivityNextAction nextAction,
        ActivityBlockedReason blockedReason) {
    public static ActivityReadResponse of(ActivityResponse activity, ActivityReadiness readiness) {
        return new ActivityReadResponse(
                activity.activityId(),
                activity.childId(),
                activity.goalId(),
                activity.activityType(),
                activity.scenarioId(),
                activity.scenarioSource(),
                activity.initialSupportLevel(),
                activity.difficultyFallbackApplied(),
                activity.status(),
                activity.assignedAt(),
                activity.startedAt(),
                activity.completedAt(),
                activity.rewardIssuedAt(),
                readiness.nextAction(),
                readiness.blockedReason());
    }
}
