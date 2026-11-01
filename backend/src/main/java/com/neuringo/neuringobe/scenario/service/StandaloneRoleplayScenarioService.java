package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 역할극 단독 활동에 승인 시나리오를 붙인다. 최초 결합은 퀴즈가 끝날 때(ScenarioAttachmentService) 붙지만, 역할극 단독 활동은 퀴즈가 없어서 아동이
 * 시작할 때 붙인다. 고르는 기준은 최초 결합과 같다. 같은 학급·같은 목표 내용(content_hash)·배정 때 고정한 레벨의 승인본 가운데 이 아동이 아직 쓰지 않은 가장
 * 오래된 것이다.
 */
@Service
public class StandaloneRoleplayScenarioService {
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final LearningGoalRepository goals;
    private final ScenarioRepository scenarios;
    private final FollowUpDifficultyService difficulties;

    public StandaloneRoleplayScenarioService(
            ActivityRepository activities,
            ChildRepository children,
            LearningGoalRepository goals,
            ScenarioRepository scenarios,
            FollowUpDifficultyService difficulties) {
        this.activities = activities;
        this.children = children;
        this.goals = goals;
        this.scenarios = scenarios;
        this.difficulties = difficulties;
    }

    /**
     * 호출하는 쪽이 활동 행을 잠근 트랜잭션 안에서 부른다. 붙였으면 true 다. 연결 쿼리가 영속성 문맥을 비우므로 true 면 호출하는 쪽이 활동을 다시 읽는다. 맞는
     * 승인본이 없으면 아무것도 바꾸지 않고, 시작은 INITIAL_ROLEPLAY_NOT_READY 로 거절된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attachIfMissing(UUID activityId) {
        var activity = activities.findById(activityId).orElse(null);
        if (activity == null
                || activity.getActivityType() != ActivityType.ROLEPLAY
                || activity.getScenarioId() != null
                || activity.getStatus() == ActivityStatus.COMPLETED
                || activity.getGoalId() == null) {
            return false;
        }
        var pinned = difficulties.pinned(activityId).orElse(null);
        var child = children.findById(activity.getChildId()).orElse(null);
        var goal = goals.findById(activity.getGoalId()).orElse(null);
        if (pinned == null
                || child == null
                || goal == null
                || goal.getContentHash() == null
                || goal.getContentHash().isBlank()) {
            return false;
        }
        return scenarios
                .findFirstUnusedApproved(
                        child.getClassId(),
                        goal.getContentHash(),
                        pinned.getScenarioLevel().name(),
                        child.getChildId())
                .map(scenarioId -> activities.attachScenarioIfEmpty(activityId, scenarioId) == 1)
                .orElse(false);
    }
}
