package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import java.util.Objects;
import java.util.Set;

/** Explicit deployment input, not an agreed public API default. */
public record RoleplayUploadPolicy(
        int maximumBytes, Set<AudioFormat> allowedFormats, long maximumFingerprintBytes) {
    public RoleplayUploadPolicy(int maximumBytes, Set<AudioFormat> allowedFormats) {
        this(maximumBytes, allowedFormats, maximumBytes);
    }

    public RoleplayUploadPolicy {
        if (maximumBytes < 1 || maximumBytes > SpeechTranscriptionRequest.MAX_AUDIO_BYTES)
            throw new IllegalArgumentException(
                    "Upload policy must fit the existing speech request bound");
        allowedFormats = Set.copyOf(Objects.requireNonNull(allowedFormats));
        if (allowedFormats.isEmpty())
            throw new IllegalArgumentException("Allowed audio formats required");
        if (maximumFingerprintBytes < maximumBytes)
            throw new IllegalArgumentException("지문 처리 상한은 음성 크기 상한보다 작을 수 없습니다.");
    }
}
