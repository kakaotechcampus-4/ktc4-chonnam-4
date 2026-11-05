package com.neuringo.neuringobe.assignment.service;

import com.neuringo.neuringobe.activity.domain.ActivityBlockedReason;
import com.neuringo.neuringobe.activity.domain.ActivityNextAction;
import com.neuringo.neuringobe.activity.domain.ActivityReadiness;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.assignment.dto.ActivityDetailResponse;
import com.neuringo.neuringobe.assignment.dto.ActivityReadResponse;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.dto.AssignedQuizItemResponse;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 활동 페이지와 준비 근거를 같은 읽기 시점으로 조립한다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ActivityReadQueryService {
    private final ActivityRepository activities;
    private final ChildAccessScope access;
    private final ActivityReadinessQueryService readiness;
    private final QuizAssignmentService quizAssignment;

    public ActivityReadQueryService(
            ActivityRepository activities,
            ChildAccessScope access,
            ActivityReadinessQueryService readiness,
            QuizAssignmentService quizAssignment) {
        this.activities = activities;
        this.access = access;
        this.readiness = readiness;
        this.quizAssignment = quizAssignment;
    }

    public List<ActivityReadResponse> list(
            UUID childId,
            ActivityStatus status,
            int page,
            int size,
            Authentication authentication) {
        access.requireOwnerOrInstructor(authentication, childId);
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "페이지 범위가 올바르지 않습니다.");
        }
        var paging = PageRequest.of(page, size);
        var selected =
                (status == null
                                ? activities.findByChildIdOrderByAssignedAtDesc(childId, paging)
                                : activities.findByChildIdAndStatusOrderByAssignedAtDesc(
                                        childId, status, paging))
                        .getContent();
        var ids = selected.stream().map(a -> a.getActivityId()).toList();
        var prepared = readiness.read(childId, ids, authentication);
        return selected.stream()
                .map(
                        a ->
                                ActivityReadResponse.of(
                                        ActivityResponse.from(a), prepared.get(a.getActivityId())))
                .toList();
    }

    public ActivityDetailResponse detail(UUID activityId, Authentication authentication) {
        var activity =
                activities.findById(activityId).orElseThrow(ActivityReadQueryService::notFound);
        access.requireOwnerOrInstructor(
                authentication, activity.getChildId(), ActivityReadQueryService::notFound);
        var prepared =
                readiness
                        .read(activity.getChildId(), List.of(activityId), authentication)
                        .get(activityId);
        return ActivityDetailResponse.of(
                ActivityReadResponse.of(ActivityResponse.from(activity), prepared),
                availableQuizItems(activityId, prepared, authentication));
    }

    private List<AssignedQuizItemResponse> availableQuizItems(
            UUID activityId, ActivityReadiness prepared, Authentication authentication) {
        if (prepared.blockedReason() == ActivityBlockedReason.QUIZ_NOT_READY) return List.of();
        try {
            return quizAssignment.list(activityId, authentication);
        } catch (ApiException failure) {
            // 이후 단계의 조회를 과거 문항의 누락·버전 변경만으로 되돌리지 않는다.
            if (prepared.nextAction() != ActivityNextAction.QUIZ
                    && ("QUIZ_ITEM_NOT_FOUND".equals(failure.getCode())
                            || "ITEM_VERSION_CONFLICT".equals(failure.getCode()))) return List.of();
            throw failure;
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }
}
