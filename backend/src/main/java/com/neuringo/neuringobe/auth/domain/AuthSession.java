package com.neuringo.neuringobe.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 강사 로그인 세션. 토큰 원문은 저장하지 않고 SHA-256 해시만 둔다(DEC-001 2절). 로그아웃하면 행을 지운다. */
@Entity
@Table(name = "auth_session")
public class AuthSession {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthSession() {}

    public AuthSession(
            UUID sessionId, UUID userId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** 만료 시각과 같거나 지난 순간부터 무효다. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
