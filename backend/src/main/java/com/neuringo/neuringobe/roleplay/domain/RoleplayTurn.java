package com.neuringo.neuringobe.roleplay.domain;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "response_type", length = 32)
    private String responseType;

    @Column(name = "strategy_used", length = 32)
    private String strategyUsed;

    @Column(name = "input_micro_goal_id")
    private UUID inputMicroGoalId;

    @Column(name = "input_support_level", length = 2)
    private String inputSupportLevel;

    @Column(name = "input_support_turn")
    private Integer inputSupportTurn;

    @Column(name = "input_prompt_count")
    private Integer inputPromptCount;

    @Column(name = "input_answer_revealed")
    private Boolean inputAnswerRevealed;

    @Column(name = "response_text", nullable = false, columnDefinition = "text")
    private String responseText;

    @Column(name = "canonical_utterance", columnDefinition = "text")
    private String canonicalUtterance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_quality", columnDefinition = "jsonb")
    private Map<String, Object> inputQuality;

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
        responseType = command.responseType();
        strategyUsed = command.strategyUsed();
        var input = command.inputContext();
        if (input != null) {
            inputMicroGoalId = input.microGoalId();
            inputSupportLevel = input.supportLevel();
            inputSupportTurn = input.supportTurn();
            inputPromptCount = input.promptCount();
            inputAnswerRevealed = input.answerRevealed();
        }
        responseText = command.responseText();
        canonicalUtterance = command.canonicalUtterance();
        var quality = command.inputQuality();
        if (quality != null) {
            inputQuality = new LinkedHashMap<>();
            inputQuality.put("input_type", quality.inputType());
            inputQuality.put("status", "ANALYZABLE");
            inputQuality.put("stt_confidence", quality.sttConfidence());
            inputQuality.put("stt_confidence_threshold", quality.sttConfidenceThreshold());
            inputQuality.put("normalization_confidence", quality.normalizationConfidence());
            inputQuality.put("meaning_preserved", true);
            inputQuality.put("personal_information_masked", quality.personalInformationMasked());
            if (quality.recoverySnapshot() != null) {
                inputQuality.put(
                        "low_confidence_retry_count", quality.recoverySnapshot().failureCount());
                inputQuality.put(
                        "text_input_suggested", quality.recoverySnapshot().textRecommended());
            }
        }
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
