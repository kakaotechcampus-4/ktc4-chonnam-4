package com.neuringo.neuringobe.activity.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ActivityService {
    private final ActivityRepository activities;
    private final ChildAccessScope access;

    public ActivityService(ActivityRepository activities, ChildAccessScope access) {
        this.activities = activities;
        this.access = access;
    }

    public List<ActivityResponse> list(
            UUID childId,
            ActivityStatus status,
            int page,
            int size,
            Authentication authentication) {
        access.requireOwnerOrInstructor(authentication, childId);
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "페이지 범위가 올바르지 않습니다.");
        }
        PageRequest paging = PageRequest.of(page, size);
        return (status == null
                        ? activities.findByChildIdOrderByAssignedAtDesc(childId, paging)
                        : activities.findByChildIdAndStatusOrderByAssignedAtDesc(
                                childId, status, paging))
                .map(ActivityResponse::from)
                .getContent();
    }

    public Activity requireActivity(UUID activityId, Authentication authentication) {
        Activity activity =
                activities
                        .findById(activityId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        access.requireOwnerOrInstructor(authentication, activity.getChildId());
        return activity;
    }
}
