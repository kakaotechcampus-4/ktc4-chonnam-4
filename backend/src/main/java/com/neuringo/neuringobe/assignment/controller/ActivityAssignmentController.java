package com.neuringo.neuringobe.assignment.controller;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.assignment.dto.ActivityDetailResponse;
import com.neuringo.neuringobe.assignment.dto.ClassroomActivityResponse;
import com.neuringo.neuringobe.assignment.dto.CreateActivityRequest;
import com.neuringo.neuringobe.assignment.service.ActivityAssignmentService;
import com.neuringo.neuringobe.assignment.service.ActivityReadQueryService;
import com.neuringo.neuringobe.assignment.service.ClassroomActivityQueryService;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityAssignmentController {

    private final ActivityAssignmentService service;
    private final SensitiveReadLog sensitiveReads;
    private final ActivityReadQueryService reads;
    private final ClassroomActivityQueryService classroomActivities;

    public ActivityAssignmentController(
            ActivityAssignmentService service,
            SensitiveReadLog sensitiveReads,
            ActivityReadQueryService reads,
            ClassroomActivityQueryService classroomActivities) {
        this.service = service;
        this.sensitiveReads = sensitiveReads;
        this.reads = reads;
        this.classroomActivities = classroomActivities;
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

    @GetMapping("/api/v1/children/{childId}/activities")
    public ApiResponse<List<?>> list(
            @PathVariable UUID childId,
            @RequestParam(required = false) ActivityStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        List<?> activities =
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.CHILD_ACTIVITIES,
                        childId,
                        () -> reads.list(childId, status, page, size, authentication));
        return ApiResponse.of(activities);
    }

    /** 학급 아동 전체의 활동을 최근 배정 순으로 돌려준다. 강사만 부른다(아동 세션은 보안 설정에서 막힌다). */
    @GetMapping("/api/v1/classrooms/{classId}/activities")
    public ApiResponse<List<ClassroomActivityResponse>> listForClassroom(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID classId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(
                sensitiveReads.read(
                        user.userId(),
                        SensitiveReadLog.Target.CLASSROOM_ACTIVITIES,
                        classId,
                        () -> classroomActivities.list(user.userId(), classId, page, size)));
    }

    @GetMapping("/api/v1/activities/{activityId}")
    public ApiResponse<ActivityDetailResponse> detail(
            @PathVariable UUID activityId, Authentication authentication) {
        ActivityDetailResponse detail =
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.ACTIVITY,
                        activityId,
                        () -> reads.detail(activityId, authentication));
        return ApiResponse.of(detail);
    }
}
