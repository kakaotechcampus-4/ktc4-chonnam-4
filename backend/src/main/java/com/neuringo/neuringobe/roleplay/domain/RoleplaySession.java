package com.neuringo.neuringobe.roleplay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "roleplay_session")
public class RoleplaySession {
    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "child_id", nullable = false)
    private UUID childId;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    @Column(name = "scenario_version", nullable = false)
    private int scenarioVersion;

    @Column(nullable = false)
    private String status;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "last_turn_number", nullable = false)
    private int lastTurnNumber;

    @Column(name = "last_turn_id")
    private UUID lastTurnId;

    @Column(name = "current_micro_goal_id")
    private UUID currentMicroGoalId;

    @Column(name = "current_support_level")
    private String currentSupportLevel;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    protected RoleplaySession() {}

    /** 새 세션의 최초 지원을 저장하며 기존 세션 복원에는 사용하지 않는다. */
    public static RoleplaySession start(
            UUID sessionId,
            UUID activityId,
            UUID childId,
            UUID scenarioId,
            int scenarioVersion,
            String initialSupportLevel,
            Instant now) {
        return start(
                sessionId,
                activityId,
                childId,
                scenarioId,
                scenarioVersion,
                initialSupportLevel,
                null,
                now);
    }

    /** 승인된 첫 질문의 목표가 명시된 신규 세션에만 함께 저장한다. */
    public static RoleplaySession start(
            UUID sessionId,
            UUID activityId,
            UUID childId,
            UUID scenarioId,
            int scenarioVersion,
            String initialSupportLevel,
            UUID initialMicroGoalId,
            Instant now) {
        Objects.requireNonNull(initialSupportLevel);
        if (scenarioVersion < 1 || !Set.of("S0", "S1", "S2", "S3").contains(initialSupportLevel)) {
            throw new IllegalArgumentException("역할극 시작 버전 또는 지원 수준이 올바르지 않습니다.");
        }
        RoleplaySession session = new RoleplaySession();
        session.sessionId = Objects.requireNonNull(sessionId);
        session.activityId = Objects.requireNonNull(activityId);
        session.childId = Objects.requireNonNull(childId);
        session.scenarioId = Objects.requireNonNull(scenarioId);
        session.scenarioVersion = scenarioVersion;
        session.status = "IN_PROGRESS";
        session.currentSupportLevel = initialSupportLevel;
        session.currentMicroGoalId = initialMicroGoalId;
        session.lastActivityAt = Objects.requireNonNull(now);
        return session;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public int getScenarioVersion() {
        return scenarioVersion;
    }

    public String getStatus() {
        return status;
    }

    public UUID getCurrentMicroGoalId() {
        return currentMicroGoalId;
    }

    public String getCurrentSupportLevel() {
        return currentSupportLevel;
    }

    public void completeNormally(Instant now) {
        if (!"COMPLETED".equals(status)) {
            status = "COMPLETED";
            lastActivityAt = now;
        }
    }

    public boolean requiresCanonicalCleanup(Instant cutoff) {
        return "COMPLETED".equals(status) || !lastActivityAt.isAfter(cutoff);
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public int getLastTurnNumber() {
        return lastTurnNumber;
    }

    public UUID getLastTurnId() {
        return lastTurnId;
    }

    public boolean matchesScenario(UUID id, int version) {
        return scenarioId.equals(id) && scenarioVersion == version;
    }

    public boolean canAdvance(long expectedVersion, int nextTurn) {
        return "IN_PROGRESS".equals(status)
                && rowVersion == expectedVersion
                && nextTurn == lastTurnNumber + 1;
    }

    public void advance(
            UUID turnId, int turnNumber, UUID goalId, String supportLevel, Instant now) {
        if (!"IN_PROGRESS".equals(status) || turnNumber != lastTurnNumber + 1)
            throw new IllegalStateException("Checkpoint cannot advance");
        lastTurnId = turnId;
        lastTurnNumber = turnNumber;
        currentMicroGoalId = goalId;
        currentSupportLevel = supportLevel;
        lastActivityAt = now;
    }
}
