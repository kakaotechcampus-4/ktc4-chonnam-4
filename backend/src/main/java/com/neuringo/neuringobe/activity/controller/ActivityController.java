package com.neuringo.neuringobe.activity.controller;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.activity.dto.CreateActivityRequest;
import com.neuringo.neuringobe.activity.service.ActivityService;
import com.neuringo.neuringobe.common.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {
    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/activities")
    public ResponseEntity<ApiResponse<ActivityResponse>> create(
            @RequestBody @Valid CreateActivityRequest request, Authentication authentication) {
        ActivityResponse created = service.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/v1/activities/" + created.activityId()))
                .body(ApiResponse.of(created));
    }

    @GetMapping("/api/v1/children/{childId}/activities")
    public ApiResponse<List<ActivityResponse>> list(
            @PathVariable UUID childId,
            @RequestParam(required = false) ActivityStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.of(service.list(childId, status, page, size, authentication));
    }
}
