package com.neuringo.neuringobe.quiz.controller;

import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.quiz.dto.AssignQuizItemRequest;
import com.neuringo.neuringobe.quiz.dto.AssignedQuizItemResponse;
import com.neuringo.neuringobe.quiz.dto.FinalizeQuizAttemptRequest;
import com.neuringo.neuringobe.quiz.dto.PutQuizAttemptRequest;
import com.neuringo.neuringobe.quiz.dto.QuizAttemptResponse;
import com.neuringo.neuringobe.quiz.dto.QuizHintResponse;
import com.neuringo.neuringobe.quiz.dto.QuizResultResponse;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import com.neuringo.neuringobe.quiz.service.QuizAttemptService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuizController {
    private final QuizAssignmentService assignments;
    private final QuizAttemptService attempts;

    public QuizController(QuizAssignmentService assignments, QuizAttemptService attempts) {
        this.assignments = assignments;
        this.attempts = attempts;
    }

    @PostMapping("/api/v1/activities/{activityId}/quiz-items")
    public ResponseEntity<ApiResponse<AssignedQuizItemResponse>> assign(
            @PathVariable UUID activityId,
            @RequestBody @Valid AssignQuizItemRequest request,
            Authentication authentication) {
        AssignedQuizItemResponse assigned = assignments.assign(activityId, request, authentication);
        return ResponseEntity.created(
                        URI.create(
                                "/api/v1/activities/"
                                        + activityId
                                        + "/quiz-items/"
                                        + assigned.activityQuizId()))
                .body(ApiResponse.of(assigned));
    }

    @GetMapping("/api/v1/activities/{activityId}/quiz-items")
    public ApiResponse<List<AssignedQuizItemResponse>> list(
            @PathVariable UUID activityId, Authentication authentication) {
        return ApiResponse.of(assignments.list(activityId, authentication));
    }

    @PutMapping("/api/v1/activity-quiz-items/{activityQuizId}/attempt")
    public ResponseEntity<ApiResponse<QuizAttemptResponse>> putAttempt(
            @PathVariable UUID activityQuizId,
            @RequestBody @Valid PutQuizAttemptRequest request,
            Authentication authentication) {
        QuizAttemptService.SavedAttempt saved =
                attempts.put(activityQuizId, request, authentication);
        ApiResponse<QuizAttemptResponse> body = ApiResponse.of(saved.response());
        if (saved.created()) {
            return ResponseEntity.created(
                            URI.create(
                                    "/api/v1/activity-quiz-items/" + activityQuizId + "/attempt"))
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v1/activity-quiz-items/{activityQuizId}/attempt")
    public ApiResponse<QuizAttemptResponse> getAttempt(
            @PathVariable UUID activityQuizId, Authentication authentication) {
        return ApiResponse.of(attempts.get(activityQuizId, authentication));
    }

    @PatchMapping("/api/v1/activity-quiz-items/{activityQuizId}/attempt")
    public ApiResponse<QuizAttemptResponse> finalizeInternal(
            @PathVariable UUID activityQuizId,
            @RequestBody @Valid FinalizeQuizAttemptRequest request,
            Authentication authentication) {
        return ApiResponse.of(attempts.finalizeInternal(activityQuizId, request, authentication));
    }

    @PostMapping("/api/v1/activity-quiz-items/{activityQuizId}/hints")
    public ResponseEntity<ApiResponse<QuizHintResponse>> issueHint(
            @PathVariable UUID activityQuizId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            Authentication authentication) {
        QuizHintResponse hint = attempts.issueHint(activityQuizId, requestKey, authentication);
        return ResponseEntity.created(
                        URI.create(
                                "/api/v1/activity-quiz-items/"
                                        + activityQuizId
                                        + "/hints/"
                                        + hint.hintId()))
                .body(ApiResponse.of(hint));
    }

    @GetMapping("/api/v1/activities/{activityId}/quiz-result")
    public ApiResponse<QuizResultResponse> result(
            @PathVariable UUID activityId, Authentication authentication) {
        return ApiResponse.of(attempts.result(activityId, authentication));
    }
}
