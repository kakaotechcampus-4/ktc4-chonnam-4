package com.neuringo.neuringobe.child.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "child_access_code")
public class ChildAccessCode {

    @Id
    @Column(name = "code_id", nullable = false)
    private UUID codeId;

    @Column(name = "child_id", nullable = false)
    private UUID childId;

    @Column(name = "instructor_id", nullable = false)
    private UUID instructorId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "code_digest", nullable = false)
    private String codeDigest;

    @Column(name = "derivation_counter", nullable = false)
    private int derivationCounter;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected ChildAccessCode() {}

    public ChildAccessCode(
            UUID codeId,
            UUID childId,
            UUID instructorId,
            UUID idempotencyKey,
            String codeDigest,
            int derivationCounter,
            Instant issuedAt,
            Instant expiresAt) {
        this.codeId = codeId;
        this.childId = childId;
        this.instructorId = instructorId;
        this.idempotencyKey = idempotencyKey;
        this.codeDigest = codeDigest;
        this.derivationCounter = derivationCounter;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public UUID getCodeId() {
        return codeId;
    }

    public UUID getChildId() {
        return childId;
    }

    public UUID getInstructorId() {
        return instructorId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCodeDigest() {
        return codeDigest;
    }

    public int getDerivationCounter() {
        return derivationCounter;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        active = false;
    }
}
