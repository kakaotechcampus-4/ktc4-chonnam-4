package com.neuringo.neuringobe.goal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 학습 목표와 역할극 추가 조건. 조건은 모두 선택이다. 상황은 장면 설명 문장이다. 시안에 상황 유형 목록이 없어 situationType 칸에 설명을 담는다. 필수 요소는
 * 아이가 대화에서 해 볼 행동이고, 피할 표현은 역할극에 나오면 안 되는 말이다.
 */
public record CreateLearningGoalRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 200) String situationType,
        @Size(max = 30) String category,
        List<@Valid GoalCharacter> characters,
        List<String> requiredElements,
        List<String> forbiddenExpressions,
        UUID parentGoalId) {}
