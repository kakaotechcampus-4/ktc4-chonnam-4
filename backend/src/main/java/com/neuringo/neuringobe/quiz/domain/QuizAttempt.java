package com.neuringo.neuringobe.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quiz_attempt")
public class QuizAttempt {
    @Id
    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "activity_quiz_id", nullable = false)
    private UUID activityQuizId;

    @Column(name = "first_response")
    private String firstResponse;

    @Column(name = "first_response_correct", nullable = false)
    private boolean firstResponseCorrect;

    @Enumerated(EnumType.STRING)
    @Column(name = "expression_match_result")
    private ExpressionMatchResult expressionMatchResult;

    @Column(name = "camera_model_version")
    private String cameraModelVersion;

    @Column(name = "technical_failure_reason")
    private String technicalFailureReason;

    @Column(name = "final_response")
    private String finalResponse;

    @Column(name = "resolved_after_hint", nullable = false)
    private boolean resolvedAfterHint;

    @Column(name = "item_correct", nullable = false)
    private boolean itemCorrect;

    @Column(name = "excluded_from_scoring", nullable = false)
    private boolean excludedFromScoring;

    @Column(name = "status", nullable = false)
    private String status;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "responded_at", nullable = false)
    private Instant respondedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    protected QuizAttempt() {}

    public QuizAttempt(
            UUID attemptId,
            UUID activityQuizId,
            String firstResponse,
            boolean firstResponseCorrect,
            ExpressionMatchResult expressionMatchResult,
            String cameraModelVersion,
            String technicalFailureReason,
            boolean excludedFromScoring,
            Instant respondedAt) {
        this.attemptId = attemptId;
        this.activityQuizId = activityQuizId;
        this.firstResponse = firstResponse;
        this.firstResponseCorrect = firstResponseCorrect;
        this.expressionMatchResult = expressionMatchResult;
        this.cameraModelVersion = cameraModelVersion;
        this.technicalFailureReason = technicalFailureReason;
        this.excludedFromScoring = excludedFromScoring;
        this.respondedAt = respondedAt;
        this.status = "DRAFT";
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getActivityQuizId() {
        return activityQuizId;
    }

    public String getFirstResponse() {
        return firstResponse;
    }

    public boolean isFirstResponseCorrect() {
        return firstResponseCorrect;
    }

    public ExpressionMatchResult getExpressionMatchResult() {
        return expressionMatchResult;
    }

    public String getCameraModelVersion() {
        return cameraModelVersion;
    }

    public String getTechnicalFailureReason() {
        return technicalFailureReason;
    }

    public String getFinalResponse() {
        return finalResponse;
    }

    public boolean isResolvedAfterHint() {
        return resolvedAfterHint;
    }

    public boolean isItemCorrect() {
        return itemCorrect;
    }

    public boolean isExcludedFromScoring() {
        return excludedFromScoring;
    }

    public String getStatus() {
        return status;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public boolean isFinalized() {
        return "FINALIZED".equals(status);
    }

    public void finalizeWith(String answer, boolean correct, boolean resolved, Instant now) {
        this.finalResponse = answer;
        this.itemCorrect = correct;
        this.resolvedAfterHint = resolved;
        this.status = "FINALIZED";
        this.finalizedAt = now;
    }
}
