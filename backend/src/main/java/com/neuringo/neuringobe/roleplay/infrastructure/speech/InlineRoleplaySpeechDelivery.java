package com.neuringo.neuringobe.roleplay.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 합성한 음성을 응답 본문 안의 data URL 로 바로 넘긴다(roleplay.speech.delivery=inline).
 *
 * <p>음성은 인증된 아동이 받은 이 응답에만 실리고 서버 어디에도 저장하지 않는다. 그래서 별도 다운로드 주소·권한 검사·보관 기한이 필요 없다. 재전송 (replayed)
 * 응답에는 음성이 실리지 않는다(RoleplayTurnResponseMapper). 상한보다 큰 음성은 싣지 않고 글만 보낸다.
 */
public final class InlineRoleplaySpeechDelivery implements RoleplaySpeechDelivery {
    /** 60자 안팎 대사의 mp3 는 수십 KB 다. 응답이 지나치게 커지지 않도록 원본 1MiB 를 넘으면 싣지 않는다. */
    public static final int DEFAULT_MAXIMUM_BYTES = 1024 * 1024;

    private final int maximumBytes;

    public InlineRoleplaySpeechDelivery() {
        this(DEFAULT_MAXIMUM_BYTES);
    }

    public InlineRoleplaySpeechDelivery(int maximumBytes) {
        if (maximumBytes < 1) throw new IllegalArgumentException("음성 상한은 양수여야 합니다.");
        this.maximumBytes = maximumBytes;
    }

    @Override
    public Optional<String> publish(UUID approvedCandidateId, SynthesizedSpeech speech) {
        Objects.requireNonNull(approvedCandidateId);
        Objects.requireNonNull(speech);
        byte[] audio = speech.audio();
        if (audio.length > maximumBytes) return Optional.empty();
        return Optional.of(
                "data:"
                        + speech.format().mediaType()
                        + ";base64,"
                        + Base64.getEncoder().encodeToString(audio));
    }
}
