package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.dto.CreateLearningGoalRequest;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import com.neuringo.neuringobe.scenario.dto.RoleplayScenarioCandidatesResponse;
import com.neuringo.neuringobe.scenario.repository.RoleplayScenarioCandidateStore;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 역할극 단독 활동의 시나리오는 강사가 배정할 때 고른다. 강사가 상황과 작은 목표를 보지 못한 채 서버가 붙이지 않는다.
 *
 * <p>후보는 이 아동의 다음 역할극 난이도 레벨, 배정할 목표와 같은 내용(content_hash)의 승인본 가운데 이 아동이 아직 쓰지 않은 것이다. 학급은 보지 않는다.
 * 다른 아동이 쓴 승인본은 강사가 재사용을 승인해야(reuseApproved) 배정한다.
 */
@Service
@Transactional(readOnly = true)
public class RoleplayScenarioSelectionService {
    private final ChildAccessScope access;
    private final LearningGoalService goals;
    private final FollowUpDifficultyService difficulties;
    private final RoleplayScenarioCandidateStore store;

    public RoleplayScenarioSelectionService(
            ChildAccessScope access,
            LearningGoalService goals,
            FollowUpDifficultyService difficulties,
            RoleplayScenarioCandidateStore store) {
        this.access = access;
        this.goals = goals;
        this.difficulties = difficulties;
        this.store = store;
    }

    /** 목표는 저장하지 않는다. 배정 화면이 보낼 목표와 같은 내용으로 해시만 계산해 후보를 찾는다. */
    public RoleplayScenarioCandidatesResponse candidates(
            UUID childId, String goalTitle, String goalCategory, Authentication authentication) {
        access.requireInstructor(authentication, childId);
        if (goalTitle == null || goalTitle.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "GOAL_REQUIRED", "이 활동에는 학습 목표가 필요합니다.");
        }
        var difficulty =
                difficulties
                        .latestForChild(childId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ROLEPLAY_DIFFICULTY_NOT_FOUND",
                                                "아직 마친 역할극이 없어 다음 난이도가 없습니다."));
        LearningGoal goal =
                goals.newGoal(
                        childId,
                        new CreateLearningGoalRequest(
                                goalTitle, null, goalCategory, null, null, null, null),
                        authentication);
        String level = difficulty.getNextLevel().name();
        return new RoleplayScenarioCandidatesResponse(
                childId,
                level,
                difficulty.getNextSupportLevel().name(),
                store.candidates(goal.getContentHash(), level, childId));
    }

    /** 배정 트랜잭션 안에서 부른다. 고른 시나리오가 지금도 승인 상태이고 목표·고정 레벨에 맞는지 확인하고, 배정이 끝날 때까지 승인 폐기와 겹치지 않게 잠근다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requireSelectable(
            UUID childId,
            LearningGoal goal,
            String pinnedLevel,
            UUID scenarioId,
            boolean reuseApproved) {
        if (scenarioId == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "SCENARIO_REQUIRED",
                    "역할극 활동에는 승인된 시나리오를 골라야 합니다.");
        }
        var scenario =
                store.lock(scenarioId)
                        .filter(found -> "APPROVED".equals(found.status()) && found.version() > 0)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_CONTENT,
                                                "UNAPPROVED_SCENARIO",
                                                "승인된 시나리오만 배정할 수 있습니다."));
        if (!Objects.equals(scenario.level(), pinnedLevel)
                || !Objects.equals(scenario.goalHash(), goal.getContentHash())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "SCENARIO_NOT_MATCHED",
                    "고른 시나리오가 이 목표나 다음 역할극 난이도와 맞지 않습니다.");
        }
        if (store.usedByChild(scenarioId, childId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "SCENARIO_ALREADY_USED", "이 아동이 이미 쓴 시나리오입니다.");
        }
        if (!reuseApproved && store.usedByOtherChildren(scenarioId, childId)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "SCENARIO_REUSE_NOT_APPROVED",
                    "다른 아동이 쓴 시나리오는 재사용을 승인해야 배정할 수 있습니다.");
        }
        return scenarioId;
    }
}
