package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficulty;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyPolicy;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.RoleplayActivityDifficulty;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.repository.FollowUpDifficultyRepository;
import com.neuringo.neuringobe.scenario.repository.RoleplayActivityDifficultyRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다음 역할극 난이도(03 VS-016)를 정하고, 역할극 단독 활동에 고정한다.
 *
 * <p>결정은 역할극이 끝난 활동마다 한 번이다. 연속 상향 후보 수는 이 아동의 가장 최근 결정에서 이어받는다. 결정 순서가 곧 역할극을 마친 순서라서, 리포트 준비가 실패해
 * 결정이 빠진 활동이 있으면 그 활동은 연속을 이어 주지 못한다(결정이 없는 활동은 건너뛴다).
 */
@Service
public class FollowUpDifficultyService {
    private final FollowUpDifficultyRepository decisions;
    private final RoleplayActivityDifficultyRepository pinned;
    private final FollowUpDifficultyPolicy policy;
    private final Clock clock;

    public FollowUpDifficultyService(
            FollowUpDifficultyRepository decisions,
            RoleplayActivityDifficultyRepository pinned,
            FollowUpDifficultyPolicy policy,
            Clock clock) {
        this.decisions = decisions;
        this.pinned = pinned;
        this.policy = policy;
        this.clock = clock;
    }

    /** 완료한 역할극의 목표별 결과로 결정한다. 같은 활동을 다시 부르면 앞의 결정을 그대로 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FollowUpDifficulty decideAfterRoleplay(
            UUID activityId, UUID childId, ScenarioLevel playedLevel, List<GoalOutcome> outcomes) {
        var existing = decisions.findByActivityId(activityId);
        if (existing.isPresent()) {
            return existing.get();
        }
        int previousCandidates =
                decisions
                        .findFirstByChildIdOrderByDecidedAtDescDecisionIdDesc(childId)
                        .map(FollowUpDifficulty::getConsecutiveUpgradeCandidates)
                        .orElse(0);
        var decision = policy.decide(playedLevel, outcomes, previousCandidates);
        return decisions.saveAndFlush(
                new FollowUpDifficulty(
                        UUID.randomUUID(), activityId, childId, decision, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Optional<FollowUpDifficulty> latestForChild(UUID childId) {
        return decisions.findFirstByChildIdOrderByDecidedAtDescDecisionIdDesc(childId);
    }

    @Transactional(readOnly = true)
    public Optional<FollowUpDifficulty> forActivity(UUID activityId) {
        return decisions.findByActivityId(activityId);
    }

    /**
     * 역할극 단독 활동을 배정할 때 부른다. 이 아동의 최신 결정이 없으면 비어 있는 값을 돌려주고, 배정하는 쪽이 거절한다. 호출하는 쪽이 아동 행을 잠근 트랜잭션
     * 안이어야 같은 아동의 역할극 완료와 섞이지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RoleplayActivityDifficulty> pinForActivity(UUID activityId, UUID childId) {
        var existing = pinned.findById(activityId);
        if (existing.isPresent()) {
            return existing;
        }
        return decisions
                .findFirstByChildIdOrderByDecidedAtDescDecisionIdDesc(childId)
                .map(
                        latest ->
                                pinned.saveAndFlush(
                                        new RoleplayActivityDifficulty(
                                                activityId, latest, clock.instant())));
    }

    @Transactional(readOnly = true)
    public Optional<RoleplayActivityDifficulty> pinned(UUID activityId) {
        return pinned.findById(activityId);
    }

    @Transactional(readOnly = true)
    public Optional<FollowUpDifficulty> decision(UUID decisionId) {
        return decisions.findById(decisionId);
    }

    /** 아동 삭제. 고정 난이도가 결정을 가리키므로 먼저 지운다. 활동보다 먼저 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteByChildIds(Collection<UUID> childIds) {
        pinned.deleteByChildIds(childIds);
        decisions.deleteByChildIds(childIds);
    }
}
