package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import com.neuringo.neuringobe.scenario.dto.ScenarioDifficultyBasis;
import com.neuringo.neuringobe.scenario.dto.ScenarioPreparationInput;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 역할극 단독 활동의 검토 입력 어댑터. 퀴즈 결과 대신 배정 때 활동에 고정한 다음 역할극 난이도(roleplay_activity_difficulty)와 그 값을 정한 결정의
 * 정책 버전을 쓴다. 아동의 최신 결정을 다시 고르지 않으므로, 배정 뒤 다른 역할극이 끝나도 이 활동의 입력은 바뀌지 않는다.
 *
 * <p>담당 강사 확인은 최초 어댑터와 같다. 활동은 담당 범위(ChildAccessScope)로, 목표는 목표 조회(강사 전용)로 확인해 아동의 직접 준비 요청을 막는다.
 */
@Service
@Transactional(readOnly = true)
public class FollowUpScenarioPreparationService {
    private final FollowUpDifficultyService followUpDifficulties;
    private final LearningGoalService goals;
    private final ChildRepository children;
    private final ChildAccessScope access;

    public FollowUpScenarioPreparationService(
            FollowUpDifficultyService followUpDifficulties,
            LearningGoalService goals,
            ChildRepository children,
            ChildAccessScope access) {
        this.followUpDifficulties = followUpDifficulties;
        this.goals = goals;
        this.children = children;
        this.access = access;
    }

    /** 배정 때 다음 역할극 난이도를 이 활동에 고정했는지. 권한과 무관한 존재 여부라 고른 뒤 prepare 가 권한을 다시 본다. */
    public boolean hasPinnedDifficulty(UUID activityId) {
        return followUpDifficulties.pinned(activityId).isPresent();
    }

    public ScenarioPreparationInput prepare(Activity activity, Authentication authentication) {
        if (activity.getActivityType() != ActivityType.ROLEPLAY) {
            throw new IllegalArgumentException("역할극 단독 활동만 받는다.");
        }
        access.requireInstructor(authentication, activity.getChildId(), this::notFound);
        var pinned =
                followUpDifficulties
                        .pinned(activity.getActivityId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.CONFLICT,
                                                "ROLEPLAY_DIFFICULTY_NOT_READY",
                                                "이 활동에 고정한 다음 역할극 난이도가 없습니다."));
        var decision =
                followUpDifficulties
                        .decision(pinned.getSourceDecisionId())
                        .orElseThrow(this::notReady);
        if (activity.getGoalId() == null) throw notReady();
        var goal = goals.get(activity.getGoalId(), authentication);
        if (!goal.childId().equals(activity.getChildId())
                || goal.contentHash() == null
                || goal.contentHash().isBlank()) {
            throw notReady();
        }
        var child = children.findById(activity.getChildId()).orElseThrow(this::notFound);
        String level = pinned.getScenarioLevel().name();
        var difficulty =
                ScenarioDifficultyBasis.followUp(
                        activity.getActivityId(),
                        activity.getChildId(),
                        goal.goalId(),
                        level,
                        pinned.getInitialSupportLevel().name(),
                        decision.getPolicyVersion(),
                        pinned.getSourceDecisionId());
        var reuseKey =
                new ScenarioPreparationInput.ReuseKey(
                        child.getClassId(), goal.contentHash(), level);
        return new ScenarioPreparationInput(difficulty, goal, reuseKey);
    }

    private ApiException notReady() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "INITIAL_SCENARIO_INPUT_NOT_READY",
                "시나리오 준비에 필요한 목표 근거를 확인할 수 없습니다.");
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }
}
