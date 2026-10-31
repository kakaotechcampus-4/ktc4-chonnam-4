package com.neuringo.neuringobe.roleplay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roleplay_start_request")
public class RoleplayStartRequest {
    @Id
    @Column(name = "request_key")
    private UUID requestKey;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "child_id", nullable = false)
    private UUID childId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoleplayStartRequest() {}

    public boolean matches(UUID activityId, UUID childId) {
        return this.activityId.equals(activityId) && this.childId.equals(childId);
    }

    public UUID getSessionId() {
        return sessionId;
    }
}
