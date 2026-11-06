package com.neuringo.neuringobe.report.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API-REC-03·04·05 의 추천(contracts/api/v1/reports-recommendations/get-recommendation.md).
 *
 * @param type REINFORCE(보완)·REPEAT(반복)·APPLY(적용)·EXTEND(확장)
 * @param suggestedGoal 다음 활동 목표 제안. 확장은 title 이 null 이라 강사가 쓴다
 * @param decision 강사의 마지막 결정. 아직 고르지 않았으면 null
 * @param decidedGoal MODIFIED_ACCEPT 때 강사가 고친 목표. 그 밖에는 null
 * @param createdGoalId 채택해도 서버가 목표·활동을 만들지 않아서 늘 null 이다. 목표는 강사가 활동을 만들 때 생긴다
 */
public record RecommendationResponse(
        UUID recommendationId,
        UUID recordId,
        UUID activityId,
        UUID childId,
        String type,
        int displayOrder,
        SuggestedGoal suggestedGoal,
        String reason,
        String reasonCode,
        String basisGoalTitle,
        List<String> evidence,
        String evidenceSufficiency,
        String decision,
        SuggestedGoal decidedGoal,
        String rejectionReason,
        UUID createdGoalId,
        String policyVersion,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt) {

    public record SuggestedGoal(String title, String category, UUID parentGoalId) {}
}
