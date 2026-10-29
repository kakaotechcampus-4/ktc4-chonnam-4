package com.neuringo.neuringobe.quiz.service;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 최초 결과를 재계산하거나 활동을 변경하지 않는 서버 내부 조회 경계다. */
@Service
@Transactional(readOnly = true)
public class InitialDifficultyQueryService {
    private final ActivityRepository activities;
    private final QuizResultRepository results;
    private final ChildAccessScope access;

    public InitialDifficultyQueryService(
            ActivityRepository activities, QuizResultRepository results, ChildAccessScope access) {
        this.activities = activities;
        this.results = results;
        this.access = access;
    }

    public InitialDifficultyEvidence requireForActivity(
            UUID activityId, Authentication authentication) {
        var activity = activities.findById(activityId).orElseThrow(this::notFound);
        access.requireOwnerOrInstructor(authentication, activity.getChildId(), this::notFound);
        return results.findAppliedInitialDifficulty(activityId, activity.getChildId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.CONFLICT,
                                        "INITIAL_DIFFICULTY_NOT_READY",
                                        "사용할 수 있는 최초 난이도 근거가 준비되지 않았습니다."));
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }
}
