package com.neuringo.neuringobe.report.service;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.report.domain.RecommendationDecision;
import com.neuringo.neuringobe.report.dto.DecideRecommendationRequest;
import com.neuringo.neuringobe.report.dto.RecommendationResponse;
import com.neuringo.neuringobe.report.repository.RecommendationStore;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 강사가 추천을 채택·고쳐서 채택·보류·거절한다(API-REC-05). 결정만 남기고 목표나 활동은 만들지 않는다. 다음 활동은 강사가 활동 만들기에서 따로 배정한다(자동 배정
 * 금지).
 *
 * <p>추천 행을 잠근 뒤 같은 요청 키를 찾는다. 같은 키에 같은 입력이면 앞 결과를 돌려주고, 입력이 다르면 409 다. 채택·고쳐서 채택·거절은 확정이라 다시 바꿀 수
 * 없고, 보류만 나중에 다른 결정으로 바꿀 수 있다.
 */
@Service
public class RecommendationDecisionService {
    private static final Set<String> FINAL = Set.of("ACCEPTED", "MODIFIED_ACCEPTED", "REJECTED");

    private final RecommendationStore recommendations;
    private final LearningReportQueryService views;
    private final ChildAccessScope access;
    private final JsonMapper json;
    private final Clock clock;

    public RecommendationDecisionService(
            RecommendationStore recommendations,
            LearningReportQueryService views,
            ChildAccessScope access,
            JsonMapper json,
            Clock clock) {
        this.recommendations = recommendations;
        this.views = views;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public RecommendationResponse decide(
            UUID recommendationId,
            UUID requestKey,
            DecideRecommendationRequest request,
            Authentication authentication) {
        var found =
                recommendations
                        .findById(recommendationId)
                        .orElseThrow(LearningReportQueryService::recommendationNotFound);
        access.requireInstructor(
                authentication,
                found.childId(),
                LearningReportQueryService::recommendationNotFound);
        var locked =
                recommendations
                        .findByIdForUpdate(recommendationId)
                        .orElseThrow(LearningReportQueryService::recommendationNotFound);

        var goal = goalOf(request);
        String goalJson = goal == null ? null : json.writeValueAsString(goal);
        String rejection =
                request.decision() == RecommendationDecision.REJECT
                        ? request.rejectionReason()
                        : null;

        var prior = recommendations.findDecisionByKey(requestKey).orElse(null);
        if (prior != null) {
            boolean same =
                    prior.recommendationId().equals(recommendationId)
                            && prior.decision().equals(request.decision().name())
                            && Objects.equals(normalize(prior.goalJson()), normalize(goalJson))
                            && Objects.equals(prior.rejectionReason(), rejection);
            if (!same) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 결정 요청에 사용한 키입니다.");
            }
            return views.toResponse(locked);
        }
        if (FINAL.contains(locked.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "RECOMMENDATION_ALREADY_DECIDED", "이미 결정한 추천입니다.");
        }

        var now = clock.instant();
        recommendations.insertDecision(
                UUID.randomUUID(),
                recommendationId,
                requestKey,
                request.decision().name(),
                goalJson,
                rejection,
                ((AuthenticatedUser) authentication.getPrincipal()).userId(),
                now);
        recommendations.updateStatus(recommendationId, request.decision().status(), now);
        return views.toResponse(recommendations.findById(recommendationId).orElseThrow());
    }

    /** 고쳐서 채택할 때만 목표를 남긴다. 제목이 없으면 고친 것이 없어서 422 다. */
    private RecommendationResponse.SuggestedGoal goalOf(DecideRecommendationRequest request) {
        if (request.decision() != RecommendationDecision.MODIFIED_ACCEPT) {
            return null;
        }
        var patch = request.goal();
        if (patch == null || patch.title() == null || patch.title().isBlank()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "GOAL_REQUIRED", "고쳐서 채택하려면 목표 제목이 필요합니다.");
        }
        return new RecommendationResponse.SuggestedGoal(
                patch.title().trim(), patch.category(), patch.parentGoalId());
    }

    /** jsonb 는 키 순서와 공백을 바꿔 돌려주므로 비교 전에 같은 모양으로 맞춘다. */
    private String normalize(String goalJson) {
        return goalJson == null ? null : json.readTree(goalJson).toString();
    }
}
