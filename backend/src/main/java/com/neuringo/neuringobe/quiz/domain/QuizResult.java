package com.neuringo.neuringobe.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quiz_result")
public class QuizResult {
    @Id
    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "valid_question_count", nullable = false)
    private int validQuestionCount;

    @Column(name = "correct_question_count", nullable = false)
    private int correctQuestionCount;

    @Column(name = "overall_accuracy")
    private BigDecimal overallAccuracy;

    @Column(name = "total_hint_count", nullable = false)
    private int totalHintCount;

    @Column(name = "resolved_after_hint_count", nullable = false)
    private int resolvedAfterHintCount;

    @Column(name = "scenario_level")
    private String scenarioLevel;

    @Column(name = "initial_support_level")
    private String initialSupportLevel;

    @Column(name = "difficulty_fallback_applied", nullable = false)
    private boolean difficultyFallbackApplied;

    @Column(name = "initial_difficulty_used", nullable = false)
    private boolean initialDifficultyUsed;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "snapshotted_at", nullable = false)
    private Instant snapshottedAt;

    protected QuizResult() {}

    public QuizResult(
            UUID activityId,
            QuizResultCalculator.Summary summary,
            InitialDifficultyPolicy.Decision decision,
            boolean initialDifficultyUsed,
            Instant snapshottedAt) {
        this.activityId = activityId;
        this.validQuestionCount = summary.validQuestionCount();
        this.correctQuestionCount = summary.correctQuestionCount();
        this.overallAccuracy =
                summary.validQuestionCount() == 0
                        ? null
                        : BigDecimal.valueOf(summary.overallAccuracy());
        this.totalHintCount = summary.totalHintCount();
        this.resolvedAfterHintCount = summary.resolvedAfterHintCount();
        this.scenarioLevel = decision.scenarioLevel();
        this.initialSupportLevel = decision.initialSupportLevel();
        this.difficultyFallbackApplied = decision.fallbackApplied();
        this.initialDifficultyUsed = initialDifficultyUsed;
        this.policyVersion = decision.policyVersion();
        this.snapshottedAt = snapshottedAt;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public int getValidQuestionCount() {
        return validQuestionCount;
    }

    public int getCorrectQuestionCount() {
        return correctQuestionCount;
    }

    public BigDecimal getOverallAccuracy() {
        return overallAccuracy;
    }

    public int getTotalHintCount() {
        return totalHintCount;
    }

    public int getResolvedAfterHintCount() {
        return resolvedAfterHintCount;
    }

    public String getScenarioLevel() {
        return scenarioLevel;
    }

    public String getInitialSupportLevel() {
        return initialSupportLevel;
    }

    public boolean isDifficultyFallbackApplied() {
        return difficultyFallbackApplied;
    }

    public boolean isInitialDifficultyUsed() {
        return initialDifficultyUsed;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }
}
