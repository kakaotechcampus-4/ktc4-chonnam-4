package com.neuringo.neuringobe.activity.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.dto.ActivityStatusResponse;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.UUID;
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

    /** 본인 아동과 담당 강사만 본다. 그 밖에는 활동이 없는 것과 같은 오류로 숨긴다. */
    public ActivityStatusResponse status(UUID activityId, Authentication authentication) {
        Activity activity =
                activities.findById(activityId).orElseThrow(ActivityService::activityNotFound);
        access.requireOwnerOrInstructor(
                authentication, activity.getChildId(), ActivityService::activityNotFound);
        return new ActivityStatusResponse(activity.getStatus());
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

    private static ResourceNotFoundException activityNotFound() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }
}
