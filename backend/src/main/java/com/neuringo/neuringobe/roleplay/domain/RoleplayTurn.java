package com.neuringo.neuringobe.roleplay.domain;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roleplay_turn")
public class RoleplayTurn {
    @Id
    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "input_fingerprint", nullable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "micro_goal_id", nullable = false)
    private UUID microGoalId;

    @Column(name = "support_level", nullable = false, length = 2)
    private String supportLevel;

    @Column(name = "response_text", nullable = false, columnDefinition = "text")
    private String responseText;

    @Column(name = "canonical_utterance", columnDefinition = "text")
    private String canonicalUtterance;

    @Column(name = "checkpoint_version", nullable = false)
    private long checkpointVersion;

    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    protected RoleplayTurn() {}

    public RoleplayTurn(RoleplayCheckpointCommand command, long version, Instant now) {
        turnId = command.turnId();
        sessionId = command.sessionId();
        requestId = command.requestId();
        idempotencyKey = command.idempotencyKey();
        inputFingerprint = command.inputFingerprint();
        turnNumber = command.turnNumber();
        candidateId = command.candidateId();
        microGoalId = command.microGoalId();
        supportLevel = command.supportLevel();
        responseText = command.responseText();
        canonicalUtterance = command.canonicalUtterance();
        checkpointVersion = version;
        committedAt = now;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public UUID getCandidateId() {
        return candidateId;
    }

    public String getInputFingerprint() {
        return inputFingerprint;
    }

    public String getResponseText() {
        return responseText;
    }

    public long getCheckpointVersion() {
        return checkpointVersion;
    }
}
