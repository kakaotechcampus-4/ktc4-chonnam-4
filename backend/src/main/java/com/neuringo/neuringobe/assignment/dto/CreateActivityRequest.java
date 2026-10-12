package com.neuringo.neuringobe.assignment.dto;

import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.goal.dto.CreateLearningGoalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 목표 내용을 같이 받아 목표 저장과 활동 배정을 한 트랜잭션에서 한다. 배정이 실패하면 목표도 남지 않는다. activityType 을 비우면 최초 결합이다(유형이 생기기 전
 * 요청과 같은 뜻). 최초 결합·역할극은 goal 이 있어야 하고, 독립 퀴즈는 없어야 한다.
 */
public record CreateActivityRequest(
        @NotNull UUID childId, ActivityType activityType, @Valid CreateLearningGoalRequest goal) {

    public ActivityType type() {
        return activityType == null ? ActivityType.FIRST_COMBINED : activityType;
    }
}
