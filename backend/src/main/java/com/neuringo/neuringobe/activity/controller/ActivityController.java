package com.neuringo.neuringobe.activity.controller;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.activity.service.ActivityService;
import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {
    private final ActivityService service;
    private final SensitiveReadLog sensitiveReads;

    public ActivityController(ActivityService service, SensitiveReadLog sensitiveReads) {
        this.service = service;
        this.sensitiveReads = sensitiveReads;
    }

    @GetMapping("/api/v1/children/{childId}/activities")
    public ApiResponse<List<ActivityResponse>> list(
            @PathVariable UUID childId,
            @RequestParam(required = false) ActivityStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        List<ActivityResponse> activities =
                service.list(childId, status, page, size, authentication);
        sensitiveReads.recordIfInstructor(
                authentication, SensitiveReadLog.Target.CHILD_ACTIVITIES, childId);
        return ApiResponse.of(activities);
    }
}
