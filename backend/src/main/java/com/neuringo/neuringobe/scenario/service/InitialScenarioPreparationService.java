package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import com.neuringo.neuringobe.quiz.service.InitialDifficultyQueryService;
import com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 강사가 시나리오를 준비할 때 사용할 목표·학급·최초 난이도를 읽기 전용으로 조립한다. */
@Service
@Transactional(readOnly = true)
public class InitialScenarioPreparationService {
    private final InitialDifficultyQueryService initialDifficulties;
    private final LearningGoalService goals;
    private final ChildRepository children;

    public InitialScenarioPreparationService(
            InitialDifficultyQueryService initialDifficulties,
            LearningGoalService goals,
            ChildRepository children) {
        this.initialDifficulties = initialDifficulties;
        this.goals = goals;
        this.children = children;
    }

    public InitialScenarioPreparation prepare(UUID activityId, Authentication authentication) {
        var evidence = initialDifficulties.requireForActivity(activityId, authentication);
        // 목표 조회가 담당 강사 권한을 확인하므로 아동의 직접 준비 요청은 여기서 막힌다.
        var goal = goals.get(evidence.goalId(), authentication);
        if (!goal.childId().equals(evidence.childId())
                || goal.contentHash() == null
                || goal.contentHash().isBlank()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INITIAL_SCENARIO_INPUT_NOT_READY",
                    "시나리오 준비에 필요한 목표 근거를 확인할 수 없습니다.");
        }
        var child =
                children.findById(evidence.childId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        var reuseKey =
                new InitialScenarioPreparation.ReuseKey(
                        child.getClassId(), goal.contentHash(), evidence.scenarioLevel());
        return new InitialScenarioPreparation(evidence, goal, reuseKey);
    }
}
