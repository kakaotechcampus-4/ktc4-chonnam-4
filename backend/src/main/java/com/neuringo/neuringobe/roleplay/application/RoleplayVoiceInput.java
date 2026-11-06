package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
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
            MessageDigest digest = newFingerprintDigest(request.format(), null);
            digest.update(bytes);
            var fingerprint = HexFormat.of().formatHex(digest.digest());
            deadline.requireActive();
            return new RoleplayVoiceInput(request, fingerprint);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** 지원 형식은 정상 입력과 같은 지문을 쓰고, 알 수 없는 형식은 원래 MIME을 구분한다. */
    public static MessageDigest newFingerprintDigest(AudioFormat format, String contentType) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            if (format != null) {
                digest.update("neuringo:voice-input:v1\0".getBytes(StandardCharsets.UTF_8));
                digest.update(format.name().getBytes(StandardCharsets.UTF_8));
            } else {
                digest.update(
                        "neuringo:unknown-voice-format:v1\0".getBytes(StandardCharsets.UTF_8));
                digest.update((byte) (contentType == null ? 0 : 1));
                if (contentType != null)
                    digest.update(contentType.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
            return digest;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
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
