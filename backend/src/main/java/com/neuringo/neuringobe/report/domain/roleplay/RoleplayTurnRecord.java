package com.neuringo.neuringobe.report.domain.roleplay;

import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 역할극에서 아동이 답한 한 번의 기록. 팀 develop 의
 * roleplay_turn·roleplay_goal_assessment·roleplay_goal_observation 이 남기는 값 중 리포트·난이도·추천에 필요한 것만
 * 담는다(input_micro_goal_id, input_support_level, input_learning_state, 응답 행동이 거부인지, 기준별 관찰 상태). 발화
 * 원문은 담지 않는다.
 *
 * @param microGoalId 아동이 답한 질문의 작은 목표
 * @param turnNumber 1부터 커지는 순서
 * @param inputSupport 그 질문을 할 때 쓴 지원 단계
 * @param refusal 응답 행동이 REFUSAL(하기 싫다고 함)인지
 * @param observations 이 답에서 확인한 학습 증거 기준별 상태. 없으면 빈 목록
 */
public record RoleplayTurnRecord(
        UUID microGoalId,
        int turnNumber,
        SupportLevel inputSupport,
        LearningState learningState,
        boolean refusal,
        List<CriterionObservation> observations) {

    public RoleplayTurnRecord {
        Objects.requireNonNull(microGoalId, "microGoalId must not be null");
        Objects.requireNonNull(inputSupport, "inputSupport must not be null");
        Objects.requireNonNull(learningState, "learningState must not be null");
        if (turnNumber < 1) {
            throw new IllegalArgumentException("turnNumber starts at 1");
        }
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public RoleplayTurnRecord(
            UUID microGoalId,
            int turnNumber,
            SupportLevel inputSupport,
            LearningState learningState,
            boolean refusal) {
        this(microGoalId, turnNumber, inputSupport, learningState, refusal, List.of());
    }
}
