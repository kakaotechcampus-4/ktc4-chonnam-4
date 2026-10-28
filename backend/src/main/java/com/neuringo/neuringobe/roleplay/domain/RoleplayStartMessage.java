package com.neuringo.neuringobe.roleplay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roleplay_start_message")
public class RoleplayStartMessage {
    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    @Column(name = "scenario_version", nullable = false)
    private int scenarioVersion;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "opening_text", nullable = false, columnDefinition = "text")
    private String openingText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoleplayStartMessage() {}

    public UUID getMessageId() {
        return messageId;
    }

    public String getOpeningText() {
        return openingText;
    }
}
