package com.neuringo.neuringobe.assignment.dto;

import com.neuringo.neuringobe.activity.domain.ActivityNextAction;
import com.neuringo.neuringobe.activity.domain.ActivityReadiness;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 아동의 화면 이동과 완료 표시에 필요한 값만 응답한다. */
public record ChildActivityResponse(
        UUID activityId,
        ActivityType activityType,
        ActivityStatus status,
        OffsetDateTime completedAt,
        ActivityNextAction nextAction) {
    public static ChildActivityResponse of(ActivityResponse activity, ActivityReadiness readiness) {
        return new ChildActivityResponse(
                activity.activityId(),
                activity.activityType(),
                activity.status(),
                activity.completedAt(),
                readiness.nextAction());
    }
}
