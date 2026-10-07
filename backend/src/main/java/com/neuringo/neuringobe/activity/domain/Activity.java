package com.neuringo.neuringobe.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "activity")
public class Activity {
    @Id
    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "child_id", nullable = false)
    private UUID childId;

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    @Column(name = "activity_type", nullable = false)
    private String activityType;

    @Column(name = "scenario_id")
    private UUID scenarioId;

    @Column(name = "scenario_source")
    private String scenarioSource;

    @Column(name = "initial_support_level")
    private String initialSupportLevel;

    @Column(name = "difficulty_fallback_applied", nullable = false)
    private boolean difficultyFallbackApplied;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ActivityStatus status;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "reward_issued_at")
    private Instant rewardIssuedAt;

    // 배정 요청의 Idempotency-Key. 같은 키로 다시 오면 새로 만들지 않고 이 활동을 돌려준다(ADR 2026-10-03 D4).
    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    protected Activity() {}

    public Activity(
            UUID activityId, UUID childId, UUID goalId, UUID idempotencyKey, Instant assignedAt) {
        this.activityId = activityId;
        this.childId = childId;
        this.goalId = goalId;
        this.idempotencyKey = idempotencyKey;
        this.activityType = "QUIZ_ROLEPLAY";
        this.status = ActivityStatus.NOT_STARTED;
        this.assignedAt = assignedAt;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public UUID getChildId() {
        return childId;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getActivityType() {
        return activityType;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public String getScenarioSource() {
        return scenarioSource;
    }

    public String getInitialSupportLevel() {
        return initialSupportLevel;
    }

    public boolean isDifficultyFallbackApplied() {
        return difficultyFallbackApplied;
    }

    public ActivityStatus getStatus() {
        return status;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getRewardIssuedAt() {
        return rewardIssuedAt;
    }

    public void start(Instant now) {
        if (status == ActivityStatus.NOT_STARTED) {
            status = ActivityStatus.IN_PROGRESS;
            startedAt = now;
        }
    }

    public void applyInitialDifficulty(String supportLevel, boolean fallbackApplied) {
        initialSupportLevel = supportLevel;
        difficultyFallbackApplied = fallbackApplied;
    }
}
