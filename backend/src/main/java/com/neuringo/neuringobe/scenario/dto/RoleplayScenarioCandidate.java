package com.neuringo.neuringobe.scenario.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 역할극 단독 배정에서 강사가 고를 승인 시나리오 하나. 강사가 상황과 작은 목표를 보고 고른다.
 *
 * @param microGoals 작은 목표 설명. 보여 주는 순서일 뿐 통과 순서가 아니다
 * @param approvedAt 승인 시각. V27 이전 승인본은 만든 시각이다
 * @param lastUsedAt 이 시나리오가 붙은 활동이 마지막으로 배정된 시각. 쓰인 적이 없으면 null
 * @param usedByOtherChildren 이 시나리오를 쓴 다른 아동 수
 * @param reuseApprovalRequired 다른 아동이 쓴 승인본이라 배정할 때 강사의 재사용 승인(reuseApproved)이 필요한지
 */
public record RoleplayScenarioCandidate(
        UUID scenarioId,
        int scenarioVersion,
        String scenarioLevel,
        String title,
        String summary,
        List<String> microGoals,
        OffsetDateTime approvedAt,
        OffsetDateTime lastUsedAt,
        int usedByOtherChildren,
        boolean reuseApprovalRequired) {

    public RoleplayScenarioCandidate withMicroGoals(List<String> goals) {
        return new RoleplayScenarioCandidate(
                scenarioId,
                scenarioVersion,
                scenarioLevel,
                title,
                summary,
                List.copyOf(goals),
                approvedAt,
                lastUsedAt,
                usedByOtherChildren,
                reuseApprovalRequired);
    }
}
