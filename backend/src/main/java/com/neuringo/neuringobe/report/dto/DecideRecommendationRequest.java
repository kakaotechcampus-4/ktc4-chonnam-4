package com.neuringo.neuringobe.report.dto;

import com.neuringo.neuringobe.report.domain.RecommendationDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * API-REC-05 강사 결정. 채택(ACCEPT)·고쳐서 채택(MODIFIED_ACCEPT)·보류(DEFER)·거절(REJECT).
 *
 * @param goal MODIFIED_ACCEPT 때 고친 목표. title 이 있어야 한다
 * @param rejectionReason REJECT 때 고르는 이유 코드(NOT_NOW·NOT_SUITABLE·ALREADY_PLANNED·OTHER)
 */
public record DecideRecommendationRequest(
        @NotNull RecommendationDecision decision,
        @Valid GoalPatch goal,
        @Pattern(regexp = "NOT_NOW|NOT_SUITABLE|ALREADY_PLANNED|OTHER") String rejectionReason) {

    public record GoalPatch(
            @Size(max = 200) String title, @Size(max = 30) String category, UUID parentGoalId) {}
}
