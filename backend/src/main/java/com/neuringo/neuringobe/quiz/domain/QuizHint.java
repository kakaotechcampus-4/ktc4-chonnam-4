package com.neuringo.neuringobe.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quiz_hint")
public class QuizHint {
    @Id
    @Column(name = "hint_id")
    private UUID hintId;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "hint_order", nullable = false)
    private int hintOrder;

    @Column(name = "hint_type", nullable = false)
    private String hintType;

    @Column(name = "hint_text", nullable = false)
    private String hintText;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected QuizHint() {}

    public QuizHint(
            UUID hintId,
            UUID attemptId,
            UUID idempotencyKey,
            int hintOrder,
            String hintType,
            String hintText,
            Instant issuedAt) {
        this.hintId = hintId;
        this.attemptId = attemptId;
        this.idempotencyKey = idempotencyKey;
        this.hintOrder = hintOrder;
        this.hintType = hintType;
        this.hintText = hintText;
        this.issuedAt = issuedAt;
    }

    public UUID getHintId() {
        return hintId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getHintOrder() {
        return hintOrder;
    }

    public String getHintType() {
        return hintType;
    }

    public String getHintText() {
        return hintText;
    }
}
