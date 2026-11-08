package com.neuringo.neuringobe.assignment.dto;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 학급 활동 목록의 한 줄. 활동 생성 응답과 같은 칸에 표에 쓰는 아동 이름과 목표 제목을 덧붙인다. 독립 퀴즈는 목표가 없어 제목이 null 이다. */
public record ClassroomActivityResponse(
        UUID activityId,
        UUID childId,
        String childName,
        UUID goalId,
        String goalTitle,
        ActivityType activityType,
        UUID scenarioId,
        String scenarioSource,
        String initialSupportLevel,
        boolean difficultyFallbackApplied,
        ActivityStatus status,
        OffsetDateTime assignedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime rewardIssuedAt) {
    public static ClassroomActivityResponse of(
            ActivityResponse activity, String childName, String goalTitle) {
        return new ClassroomActivityResponse(
                activity.activityId(),
                activity.childId(),
                childName,
                activity.goalId(),
                goalTitle,
                activity.activityType(),
                activity.scenarioId(),
                activity.scenarioSource(),
                activity.initialSupportLevel(),
                activity.difficultyFallbackApplied(),
                activity.status(),
                activity.assignedAt(),
                activity.startedAt(),
                activity.completedAt(),
                activity.rewardIssuedAt());
    }
}
