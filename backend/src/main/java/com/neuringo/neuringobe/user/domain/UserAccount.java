package com.neuringo.neuringobe.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "org_name")
    private String orgName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserAccount() {}

    /** 가입·로그인에서 같은 이메일을 같은 값으로 보도록 앞뒤 공백을 지우고 소문자로 바꾼다(DEC-001 6절). */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public UserAccount(
            UUID userId,
            String email,
            String passwordHash,
            String name,
            String orgName,
            UserRole role,
            AccountStatus status,
            Instant createdAt) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.orgName = orgName;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getOrgName() {
        return orgName;
    }

    public UserRole getRole() {
        return role;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
