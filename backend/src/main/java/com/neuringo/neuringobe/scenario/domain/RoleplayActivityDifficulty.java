package com.neuringo.neuringobe.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 역할극 단독 활동에 배정 시점에 고정한 난이도. 역할극 시작은 이 값으로 승인 시나리오와 시작 지원을 고른다. 배정 뒤 다른 역할극이 끝나 다음 난이도가 바뀌어도 이 활동은
 * 바뀌지 않는다.
 */
@Entity
@Table(name = "roleplay_activity_difficulty")
public class RoleplayActivityDifficulty {
    @Id
    @Column(name = "activity_id")
    private UUID activityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario_level", nullable = false, updatable = false)
    private ScenarioLevel scenarioLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "initial_support_level", nullable = false, updatable = false)
    private SupportLevel initialSupportLevel;

    @Column(name = "source_decision_id", nullable = false, updatable = false)
    private UUID sourceDecisionId;

    @Column(name = "pinned_at", nullable = false, updatable = false)
    private Instant pinnedAt;

    protected RoleplayActivityDifficulty() {}

    public RoleplayActivityDifficulty(
            UUID activityId, FollowUpDifficulty source, Instant pinnedAt) {
        this.activityId = activityId;
        this.scenarioLevel = source.getNextLevel();
        this.initialSupportLevel = source.getNextSupportLevel();
        this.sourceDecisionId = source.getDecisionId();
        this.pinnedAt = pinnedAt;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public ScenarioLevel getScenarioLevel() {
        return scenarioLevel;
    }

    public SupportLevel getInitialSupportLevel() {
        return initialSupportLevel;
    }

    public UUID getSourceDecisionId() {
        return sourceDecisionId;
    }

    public Instant getPinnedAt() {
        return pinnedAt;
    }
}
