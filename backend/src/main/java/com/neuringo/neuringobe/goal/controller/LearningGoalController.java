package com.neuringo.neuringobe.goal.controller;

import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.goal.dto.CreateLearningGoalRequest;
import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LearningGoalController {
    private final LearningGoalService service;
    private final SensitiveReadLog sensitiveReads;

    public LearningGoalController(LearningGoalService service, SensitiveReadLog sensitiveReads) {
        this.service = service;
        this.sensitiveReads = sensitiveReads;
    }

    @PostMapping("/api/v1/children/{childId}/learning-goals")
    public ResponseEntity<ApiResponse<LearningGoalResponse>> create(
            @PathVariable UUID childId,
            @RequestBody @Valid CreateLearningGoalRequest request,
            Authentication authentication) {
        LearningGoalResponse created = service.create(childId, request, authentication);
        return ResponseEntity.created(URI.create("/api/v1/learning-goals/" + created.goalId()))
                .body(ApiResponse.of(created));
    }

    @GetMapping("/api/v1/children/{childId}/learning-goals")
    public ApiResponse<List<LearningGoalResponse>> list(
            @PathVariable UUID childId, Authentication authentication) {
        List<LearningGoalResponse> goals =
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.CHILD_GOALS,
                        childId,
                        () -> service.list(childId, authentication));
        return ApiResponse.of(goals);
    }

    @GetMapping("/api/v1/learning-goals/{goalId}")
    public ApiResponse<LearningGoalResponse> get(
            @PathVariable UUID goalId, Authentication authentication) {
        LearningGoalResponse goal =
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.GOAL,
                        goalId,
                        () -> service.get(goalId, authentication));
        return ApiResponse.of(goal);
    }
}
