package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.quiz.service.InitialDifficultyQueryService;
import com.neuringo.neuringobe.roleplay.dto.InitialRoleplayStartInput;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 최초 난이도와 연결된 승인 버전을 확인하며 세션을 만들거나 기존 지원 수준을 덮어쓰지 않는다. */
@Service
@Transactional(readOnly = true)
public class InitialRoleplayStartQueryService {
    private final RoleplayRequestScopeService scopes;
    private final InitialDifficultyQueryService difficulties;
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final LearningGoalRepository goals;
    private final ScenarioRepository scenarios;
    private final RoleplaySessionRepository sessions;

    public InitialRoleplayStartQueryService(
            RoleplayRequestScopeService scopes,
            InitialDifficultyQueryService difficulties,
            ActivityRepository activities,
            ChildRepository children,
            LearningGoalRepository goals,
            ScenarioRepository scenarios,
            RoleplaySessionRepository sessions) {
        this.scopes = scopes;
        this.difficulties = difficulties;
        this.activities = activities;
        this.children = children;
        this.goals = goals;
        this.scenarios = scenarios;
        this.sessions = sessions;
    }

    public InitialRoleplayStartInput requireForActivity(
            UUID activityId, Authentication authentication) {
        UUID childId = scopes.requireChildId(authentication);
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
                        .findMatchingApprovedVersion(
                                activity.getScenarioId(),
                                child.getClassId(),
                                goal.getContentHash(),
                                evidence.scenarioLevel())
                        .orElseThrow(this::notReady);
        return new InitialRoleplayStartInput(evidence, version);
    }

    private ApiException notReady() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "INITIAL_ROLEPLAY_NOT_READY",
                "최초 역할극 시작에 필요한 저장 근거가 준비되지 않았습니다.");
    }
}
