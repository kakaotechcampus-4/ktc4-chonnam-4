package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable request snapshot. Digest covers versioned voice kind, canonical format and exact bytes,
 * not waveform semantics.
 */
public final class RoleplayVoiceInput {
    private final SpeechTranscriptionRequest request;
    private final String fingerprint;

    private RoleplayVoiceInput(SpeechTranscriptionRequest request, String fingerprint) {
        this.request = request;
        this.fingerprint = fingerprint;
    }

    /** Caller owns and closes audio; called inside the bounded worker before idempotent lookup. */
    public static RoleplayVoiceInput prepare(
            RoleplayAudioResource audio, AiTraceContext trace, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(trace);
        var request = deadline.withinBudget(Objects.requireNonNull(audio)::request);
        if (request.currentAttempt() != 1 || !trace.equals(request.traceContext()))
            throw new IllegalArgumentException(
                    "Upload does not match a new authenticated voice input");
        byte[] bytes = request.audio();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("neuringo:voice-input:v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(request.format().name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            var fingerprint = HexFormat.of().formatHex(digest.digest());
            deadline.requireActive();
            return new RoleplayVoiceInput(request, fingerprint);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public SpeechTranscriptionRequest request() {
        return request;
    }

    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public String toString() {
        return "RoleplayVoiceInput[content=redacted]";
    }
}
