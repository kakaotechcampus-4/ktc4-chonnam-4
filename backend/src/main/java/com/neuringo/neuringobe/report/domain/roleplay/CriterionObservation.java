package com.neuringo.neuringobe.report.domain.roleplay;

import java.util.Objects;

/**
 * 답 하나에서 승인된 학습 증거 기준 하나가 어떻게 나타났는지. 팀 develop 의 roleplay_goal_observation(criterion_reference,
 * observation_status)과 같은 값이다. criterion 은 승인된 시나리오의 기준 설명이고, 아동 발화가 아니다.
 *
 * @param criterion 기준 설명. 명사구로 쓴다(예: "넘어져서 아프다는 이유").
 */
public record CriterionObservation(String criterion, Status status) {

    /** 원인 판단 프롬프트의 관찰 상태 코드와 같다. */
    public enum Status {
        /** 분명히 말함 */
        EXPLICIT,
        /** 간접적으로 말함 */
        IMPLICIT,
        /** 말하지 않음 */
        NOT_EXPRESSED,
        /** 잘못 이해함 */
        MISUNDERSTOOD,
        /** 시나리오·이전 응답과 모순 */
        CONTRADICTORY
    }

    public CriterionObservation {
        Objects.requireNonNull(status, "status must not be null");
        if (criterion == null || criterion.isBlank()) {
            throw new IllegalArgumentException("criterion must not be blank");
        }
    }
}
