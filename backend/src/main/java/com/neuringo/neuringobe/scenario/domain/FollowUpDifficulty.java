package com.neuringo.neuringobe.scenario.domain;

import com.neuringo.neuringobe.common.time.StoredTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 역할극 한 건을 마친 뒤 정한 다음 역할극 난이도(03 VS-016). 점수율은 GoalScoreRate 의 6배 정수 그대로 둔다(score / scoreMax). 다음
 * 결정은 이 아동의 가장 최근 행의 consecutiveUpgradeCandidates 를 입력으로 쓴다.
 */
@Entity
@Table(name = "follow_up_difficulty_decision")
public class FollowUpDifficulty {
    @Id
    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "activity_id", nullable = false, updatable = false)
    private UUID activityId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_level", nullable = false, updatable = false)
    private ScenarioLevel previousLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_level", nullable = false, updatable = false)
    private ScenarioLevel nextLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_support_level", nullable = false, updatable = false)
    private SupportLevel nextSupportLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private FollowUpDifficultyDecision.Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private FollowUpDifficultyDecision.Reason reason;

    @Column(name = "score_numerator", nullable = false, updatable = false)
    private int scoreNumerator;

    @Column(name = "score_denominator", nullable = false, updatable = false)
    private int scoreDenominator;

    @Column(name = "consecutive_upgrade_candidates", nullable = false, updatable = false)
    private int consecutiveUpgradeCandidates;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private String policyVersion;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    protected FollowUpDifficulty() {}

    public FollowUpDifficulty(
            UUID decisionId,
            UUID activityId,
            UUID childId,
            FollowUpDifficultyDecision decision,
            Instant decidedAt) {
        this.decisionId = decisionId;
        this.activityId = activityId;
        this.childId = childId;
        this.previousLevel = decision.previousLevel();
        this.nextLevel = decision.nextLevel();
        this.nextSupportLevel = decision.nextLevel().startSupport();
        this.direction = decision.direction();
        this.reason = decision.reason();
        this.scoreNumerator = decision.scoreRate().scaledPoints();
        this.scoreDenominator = decision.scoreRate().maxScaledPoints();
        this.consecutiveUpgradeCandidates = decision.consecutiveUpgradeCandidates();
        this.policyVersion = decision.policyVersion();
        this.decidedAt = StoredTime.normalize(decidedAt);
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public UUID getChildId() {
        return childId;
    }

    public ScenarioLevel getPreviousLevel() {
        return previousLevel;
    }

    public ScenarioLevel getNextLevel() {
        return nextLevel;
    }

    public SupportLevel getNextSupportLevel() {
        return nextSupportLevel;
    }

    public FollowUpDifficultyDecision.Direction getDirection() {
        return direction;
    }

    public FollowUpDifficultyDecision.Reason getReason() {
        return reason;
    }

    public int getScoreNumerator() {
        return scoreNumerator;
    }

    public int getScoreDenominator() {
        return scoreDenominator;
    }

    public int getConsecutiveUpgradeCandidates() {
        return consecutiveUpgradeCandidates;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
