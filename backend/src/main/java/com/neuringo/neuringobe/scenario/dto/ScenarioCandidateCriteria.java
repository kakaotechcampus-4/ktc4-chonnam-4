package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import java.util.List;
import java.util.UUID;

/** 요청 조건이며, 승인 또는 콘텐츠 적합성 확인 결과가 아니다. */
public record ScenarioCandidateCriteria(
        UUID instructorId,
        UUID childId,
        ScenarioApprovalConditions requestedConditions,
        List<String> forbiddenExpressions) {
    public ScenarioCandidateCriteria {
        forbiddenExpressions = List.copyOf(forbiddenExpressions);
    }
}
