package com.neuringo.neuringobe.assignment.dto;

import com.neuringo.neuringobe.goal.dto.CreateLearningGoalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** 목표 내용을 같이 받아 목표 저장과 활동 배정을 한 트랜잭션에서 한다. 배정이 실패하면 목표도 남지 않는다. */
public record CreateActivityRequest(
        @NotNull UUID childId, @NotNull @Valid CreateLearningGoalRequest goal) {}
