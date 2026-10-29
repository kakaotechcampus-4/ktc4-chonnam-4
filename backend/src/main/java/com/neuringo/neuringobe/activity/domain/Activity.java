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

    // 독립 퀴즈는 학습 목표가 없어 null 이다(V10).
    @Column(name = "goal_id")
    private UUID goalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, updatable = false)
    private ActivityType activityType;

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
            UUID activityId,
            UUID childId,
            ActivityType activityType,
            UUID goalId,
            UUID idempotencyKey,
            Instant assignedAt) {
        this.activityId = activityId;
        this.childId = childId;
        this.activityType = activityType;
        this.goalId = goalId;
        this.idempotencyKey = idempotencyKey;
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

    public ActivityType getActivityType() {
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

    /**
     * 완료와 스탬프 지급을 함께 기록한다. 이미 완료된 활동이면 아무것도 바꾸지 않고 false 를 돌려준다. 동시 요청에도 한 번만 true 가 나오려면 호출하는 쪽이
     * 활동 행을 잠근 상태여야 한다.
     */
    public boolean complete(Instant now) {
        if (status == ActivityStatus.COMPLETED) {
            return false;
        }
        status = ActivityStatus.COMPLETED;
        if (startedAt == null) {
            startedAt = now;
        }
        completedAt = now;
        rewardIssuedAt = now;
        return true;
    }

    public void applyInitialDifficulty(String supportLevel, boolean fallbackApplied) {
        initialSupportLevel = supportLevel;
        difficultyFallbackApplied = fallbackApplied;
    }
}
