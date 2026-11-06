package com.neuringo.neuringobe.goal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 학습 목표와 역할극 추가 조건. 조건은 모두 선택이다. 상황은 장면 설명 문장이다. 시안에 상황 유형 목록이 없어 situationType 칸에 설명을 담는다. 필수 요소는
 * 아이가 대화에서 해 볼 행동이고, 피할 표현은 역할극에 나오면 안 되는 말이다.
 *
 * <p>세 목록에는 개수와 항목 글자 수 상한을 둔다. 저장한 조건은 시나리오 생성 입력에 그대로 들어가므로, 제한이 없으면 큰 요청 하나가 생성 비용과 지연을 키운다. 아이
 * 역할극에 맞는 크기로 등장인물 5명, 필수 요소 5개(각 50자), 피할 표현 10개(각 30자)까지 받는다. 개수는 정리(중복 제거) 전 요청 기준이다.
 */
public record CreateLearningGoalRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 200) String situationType,
        @Size(max = 30) String category,
        @Size(max = 5) List<@Valid GoalCharacter> characters,
        @Size(max = 5) List<@Size(max = 50) String> requiredElements,
        @Size(max = 10) List<@Size(max = 30) String> forbiddenExpressions,
        UUID parentGoalId) {}
