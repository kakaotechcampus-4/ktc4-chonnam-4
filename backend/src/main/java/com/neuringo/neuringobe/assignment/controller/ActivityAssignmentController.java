package com.neuringo.neuringobe.assignment.controller;

import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.assignment.dto.ActivityDetailResponse;
import com.neuringo.neuringobe.assignment.dto.CreateActivityRequest;
import com.neuringo.neuringobe.assignment.service.ActivityAssignmentService;
import com.neuringo.neuringobe.common.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityAssignmentController {

    private final ActivityAssignmentService service;

    public ActivityAssignmentController(ActivityAssignmentService service) {
        this.service = service;
    }

    /** 같은 Idempotency-Key 로 다시 오면 처음 만든 활동을 200 으로 돌려준다. 응답을 못 받은 화면이 같은 키로 다시 보내도 활동은 하나다. */
    @PostMapping("/api/v1/activities")
    public ResponseEntity<ApiResponse<ActivityResponse>> assign(
            @RequestBody @Valid CreateActivityRequest request,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            Authentication authentication) {
        ActivityAssignmentService.Assignment result =
                service.assign(request, requestKey, authentication);
        ApiResponse<ActivityResponse> body = ApiResponse.of(result.activity());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(
                        URI.create("/api/v1/activities/" + result.activity().activityId()))
                .body(body);
    }

    @GetMapping("/api/v1/activities/{activityId}")
    public ApiResponse<ActivityDetailResponse> detail(
            @PathVariable UUID activityId, Authentication authentication) {
        return ApiResponse.of(service.detail(activityId, authentication));
    }
}
