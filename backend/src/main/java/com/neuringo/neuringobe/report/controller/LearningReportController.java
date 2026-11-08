package com.neuringo.neuringobe.report.controller;

import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.report.dto.DecideRecommendationRequest;
import com.neuringo.neuringobe.report.dto.LearningReportResponse;
import com.neuringo.neuringobe.report.dto.RecommendationResponse;
import com.neuringo.neuringobe.report.dto.RoleplayDifficultyResponse;
import com.neuringo.neuringobe.report.service.LearningReportQueryService;
import com.neuringo.neuringobe.report.service.RecommendationDecisionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 강사용 학습 리포트·추천·다음 역할극 난이도(contracts/api/v1/reports-recommendations). 모두 담당 강사 Bearer 전용이다. */
@RestController
public class LearningReportController {
    private final LearningReportQueryService queries;
    private final RecommendationDecisionService decisions;
    private final SensitiveReadLog sensitiveReads;

    public LearningReportController(
            LearningReportQueryService queries,
            RecommendationDecisionService decisions,
            SensitiveReadLog sensitiveReads) {
        this.queries = queries;
        this.decisions = decisions;
        this.sensitiveReads = sensitiveReads;
    }

    @GetMapping("/api/v1/activities/{activityId}/learning-record")
    public ApiResponse<LearningReportResponse> getLearningReport(
            @PathVariable UUID activityId, Authentication authentication) {
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.LEARNING_REPORT,
                        activityId,
                        () -> queries.getByActivity(activityId, authentication)));
    }

    @GetMapping("/api/v1/learning-records")
    public ApiResponse<List<LearningReportResponse>> listLearningReports(
            @RequestParam UUID childId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.CHILD_LEARNING_REPORTS,
                        childId,
                        () -> queries.listByChild(childId, page, size, authentication)));
    }

    @GetMapping("/api/v1/learning-records/{recordId}/recommendations")
    public ApiResponse<List<RecommendationResponse>> listRecommendations(
            @PathVariable UUID recordId, Authentication authentication) {
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.RECOMMENDATION,
                        recordId,
                        () -> queries.listByRecord(recordId, authentication)));
    }

    @GetMapping("/api/v1/recommendations/{recommendationId}")
    public ApiResponse<RecommendationResponse> getRecommendation(
            @PathVariable UUID recommendationId, Authentication authentication) {
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.RECOMMENDATION,
                        recommendationId,
                        () -> queries.getRecommendation(recommendationId, authentication)));
    }

    /** 같은 Idempotency-Key 로 같은 결정을 다시 보내면 앞 결과를 200 으로 돌려준다. */
    @PatchMapping("/api/v1/recommendations/{recommendationId}")
    public ApiResponse<RecommendationResponse> decideRecommendation(
            @PathVariable UUID recommendationId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @RequestBody @Valid DecideRecommendationRequest request,
            Authentication authentication) {
        return ApiResponse.of(
                decisions.decide(recommendationId, requestKey, request, authentication));
    }

    @GetMapping("/api/v1/children/{childId}/roleplay-difficulty")
    public ApiResponse<RoleplayDifficultyResponse> getRoleplayDifficulty(
            @PathVariable UUID childId, Authentication authentication) {
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.ROLEPLAY_DIFFICULTY,
                        childId,
                        () -> queries.roleplayDifficulty(childId, authentication)));
    }
}
