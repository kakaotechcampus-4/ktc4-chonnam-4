package com.neuringo.neuringobe.report.service;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.report.application.ReportNarrative;
import com.neuringo.neuringobe.report.application.ReportSnapshot;
import com.neuringo.neuringobe.report.dto.LearningReportResponse;
import com.neuringo.neuringobe.report.dto.RecommendationResponse;
import com.neuringo.neuringobe.report.dto.RoleplayDifficultyResponse;
import com.neuringo.neuringobe.report.repository.LearningReportStore;
import com.neuringo.neuringobe.report.repository.RecommendationStore;
import com.neuringo.neuringobe.scenario.service.FollowUpDifficultyService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 강사의 리포트·추천·다음 난이도 조회. 담당 강사만 보고, 남의 자원은 없는 자원과 같은 404 로 숨긴다(ChildAccessScope).
 *
 * <p>리포트는 COMPLETED 만 보인다. 아직 생성 중이거나 생성에 실패한 리포트는 없는 리포트와 구분하지 않는다(계약 API-REC-01). 추천은 LLM 결과와
 * 상관없이 리포트 준비 때 만들어져서 리포트가 보이지 않아도 볼 수 있다.
 */
@Service
@Transactional(readOnly = true)
public class LearningReportQueryService {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final ActivityRepository activities;
    private final LearningReportStore reports;
    private final RecommendationStore recommendations;
    private final FollowUpDifficultyService difficulties;
    private final ChildAccessScope access;
    private final JsonMapper json;

    public LearningReportQueryService(
            ActivityRepository activities,
            LearningReportStore reports,
            RecommendationStore recommendations,
            FollowUpDifficultyService difficulties,
            ChildAccessScope access,
            JsonMapper json) {
        this.activities = activities;
        this.reports = reports;
        this.recommendations = recommendations;
        this.difficulties = difficulties;
        this.access = access;
        this.json = json;
    }

    public LearningReportResponse getByActivity(UUID activityId, Authentication authentication) {
        var activity =
                activities
                        .findById(activityId)
                        .orElseThrow(LearningReportQueryService::recordNotFound);
        access.requireInstructor(
                authentication, activity.getChildId(), LearningReportQueryService::recordNotFound);
        return reports.findCompletedByActivity(activityId)
                .map(this::toResponse)
                .orElseThrow(LearningReportQueryService::recordNotFound);
    }

    public List<LearningReportResponse> listByChild(
            UUID childId, int page, int size, Authentication authentication) {
        access.requireInstructor(authentication, childId);
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "페이지 범위가 올바르지 않습니다.");
        }
        return reports.findCompletedByChild(childId, page, size).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<RecommendationResponse> listByRecord(UUID recordId, Authentication authentication) {
        UUID childId =
                reports.findRecordChild(recordId)
                        .orElseThrow(LearningReportQueryService::recordNotFound);
        access.requireInstructor(
                authentication, childId, LearningReportQueryService::recordNotFound);
        return recommendations.findByRecord(recordId).stream().map(this::toResponse).toList();
    }

    public RecommendationResponse getRecommendation(
            UUID recommendationId, Authentication authentication) {
        var recommendation =
                recommendations
                        .findById(recommendationId)
                        .orElseThrow(LearningReportQueryService::recommendationNotFound);
        access.requireInstructor(
                authentication,
                recommendation.childId(),
                LearningReportQueryService::recommendationNotFound);
        return toResponse(recommendation);
    }

    public RoleplayDifficultyResponse roleplayDifficulty(
            UUID childId, Authentication authentication) {
        access.requireInstructor(authentication, childId);
        return difficulties
                .latestForChild(childId)
                .map(
                        decision ->
                                RoleplayDifficultyResponse.from(
                                        decision,
                                        reports.findRecordIdByActivity(decision.getActivityId())
                                                .orElse(null)))
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "ROLEPLAY_DIFFICULTY_NOT_FOUND",
                                        "아직 마친 역할극이 없어 다음 난이도가 없습니다."));
    }

    RecommendationResponse toResponse(RecommendationStore.StoredRecommendation row) {
        var latest = recommendations.findLatestDecision(row.recommendationId()).orElse(null);
        return new RecommendationResponse(
                row.recommendationId(),
                row.recordId(),
                row.activityId(),
                row.childId(),
                row.type(),
                row.displayOrder(),
                json.readValue(row.suggestedGoalJson(), RecommendationResponse.SuggestedGoal.class),
                row.reason(),
                row.reasonCode(),
                row.basisGoalTitle(),
                json.readValue(row.evidenceJson(), STRINGS),
                row.evidenceSufficiency(),
                latest == null ? null : latest.decision(),
                latest == null || latest.goalJson() == null
                        ? null
                        : json.readValue(
                                latest.goalJson(), RecommendationResponse.SuggestedGoal.class),
                latest == null ? null : latest.rejectionReason(),
                null,
                row.policyVersion(),
                koreanTime(row.createdAt()),
                koreanTime(row.decidedAt()));
    }

    private LearningReportResponse toResponse(LearningReportStore.StoredReport report) {
        var snapshot = json.readValue(report.snapshotJson(), ReportSnapshot.class);
        return new LearningReportResponse(
                report.recordId(),
                report.activityId(),
                report.childId(),
                koreanTime(report.recordCreatedAt()),
                report.summary(),
                snapshot.quiz(),
                snapshot.roleplay(),
                snapshot.difficultyDecision(),
                snapshot.metrics(),
                report.itemsJson() == null
                        ? null
                        : json.readValue(report.itemsJson(), ReportNarrative.class));
    }

    private static OffsetDateTime koreanTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.ofHours(9));
    }

    static ResourceNotFoundException recordNotFound() {
        return new ResourceNotFoundException("LEARNING_RECORD_NOT_FOUND", "학습 리포트를 찾을 수 없습니다.");
    }

    static ResourceNotFoundException recommendationNotFound() {
        return new ResourceNotFoundException("RECOMMENDATION_NOT_FOUND", "추천을 찾을 수 없습니다.");
    }
}
