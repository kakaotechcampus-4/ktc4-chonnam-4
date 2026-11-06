package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.quiz.service.InitialDifficultyQueryService;
import com.neuringo.neuringobe.roleplay.dto.InitialRoleplayStartInput;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import com.neuringo.neuringobe.scenario.service.FollowUpDifficultyService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 난이도와 연결된 승인 버전을 확인하며 세션을 만들거나 기존 지원 수준을 덮어쓰지 않는다.
 *
 * <p>역할극 단독 활동은 퀴즈 결과 대신 배정 때 고정한 다음 역할극 난이도(roleplay_activity_difficulty)와 강사가 고른 시나리오를 쓴다. 세션 생성
 * 쪽 코드가 같은 입력 모양을 쓰도록, 그 레벨·시작 지원을 문항 수 0 인 난이도 근거로 옮겨 넘긴다. 정책 버전은 그 난이도를 정한 결정의 버전이다.
 */
@Service
@Transactional(readOnly = true)
public class InitialRoleplayStartQueryService {
    private final RoleplayRequestScopeService scopes;
    private final InitialDifficultyQueryService difficulties;
    private final FollowUpDifficultyService followUpDifficulties;
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final LearningGoalRepository goals;
    private final ScenarioRepository scenarios;
    private final RoleplaySessionRepository sessions;

    public InitialRoleplayStartQueryService(
            RoleplayRequestScopeService scopes,
            InitialDifficultyQueryService difficulties,
            FollowUpDifficultyService followUpDifficulties,
            ActivityRepository activities,
            ChildRepository children,
            LearningGoalRepository goals,
            ScenarioRepository scenarios,
            RoleplaySessionRepository sessions) {
        this.scopes = scopes;
        this.difficulties = difficulties;
        this.followUpDifficulties = followUpDifficulties;
        this.activities = activities;
        this.children = children;
        this.goals = goals;
        this.scenarios = scenarios;
        this.sessions = sessions;
    }

    public InitialRoleplayStartInput requireForActivity(
            UUID activityId, Authentication authentication) {
        UUID childId = scopes.requireChildId(authentication);
        var standalone =
                activities
                        .findById(activityId)
                        .filter(found -> found.getActivityType() == ActivityType.ROLEPLAY);
        if (standalone.isPresent()) {
            return requireForStandalone(standalone.get(), childId);
        }
        var evidence = difficulties.requireForActivity(activityId, authentication);
        var activity = activities.findById(activityId).orElseThrow(this::notReady);
        if (!childId.equals(evidence.childId())
                || activity.getStatus() == ActivityStatus.COMPLETED
                || activity.getScenarioId() == null
                || sessions.existsByActivityId(activityId)) {
            throw notReady();
        }
        var child = children.findById(childId).orElseThrow(this::notReady);
        var goal = goals.findById(evidence.goalId()).orElseThrow(this::notReady);
        if (!goal.getChildId().equals(childId)
                || goal.getContentHash() == null
                || goal.getContentHash().isBlank()) {
            throw notReady();
        }
        var version =
                scenarios
                        .findAssignedApprovedVersion(
                                activityId, activity.getScenarioId(), evidence.scenarioLevel())
                        .orElseThrow(this::notReady);
        return new InitialRoleplayStartInput(evidence, version);
    }

    private InitialRoleplayStartInput requireForStandalone(Activity activity, UUID childId) {
        UUID activityId = activity.getActivityId();
        if (!childId.equals(activity.getChildId())
                || activity.getStatus() == ActivityStatus.COMPLETED
                || activity.getScenarioId() == null
                || activity.getGoalId() == null
                || sessions.existsByActivityId(activityId)) {
            throw notReady();
        }
        var pinned = followUpDifficulties.pinned(activityId).orElseThrow(this::notReady);
        var source =
                followUpDifficulties
                        .decision(pinned.getSourceDecisionId())
                        .orElseThrow(this::notReady);
        var goal = goals.findById(activity.getGoalId()).orElseThrow(this::notReady);
        if (!goal.getChildId().equals(childId)
                || goal.getContentHash() == null
                || goal.getContentHash().isBlank()) {
            throw notReady();
        }
        // 강사가 배정 때 고른 승인본이다. 학급은 보지 않는다. 배정 뒤 폐기됐으면 시작하지 않는다.
        String level = pinned.getScenarioLevel().name();
        var version =
                scenarios
                        .findApprovedVersionForGoal(
                                activity.getScenarioId(), goal.getContentHash(), level)
                        .orElseThrow(this::notReady);
        var difficulty =
                new InitialDifficultyEvidence(
                        activityId,
                        childId,
                        goal.getGoalId(),
                        0,
                        0,
                        level,
                        pinned.getInitialSupportLevel().name(),
                        false,
                        null,
                        source.getPolicyVersion());
        return new InitialRoleplayStartInput(difficulty, version);
    }

    private ApiException notReady() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "INITIAL_ROLEPLAY_NOT_READY",
                "최초 역할극 시작에 필요한 저장 근거가 준비되지 않았습니다.");
    }
}
