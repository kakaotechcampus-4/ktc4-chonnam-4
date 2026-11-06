package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import java.util.Optional;
import java.util.UUID;

/**
 * 음성 전달 없이 글자로만 대화하는 서버용 기본 부품. 음성 주소를 만들지 않으므로 응답의 speech 는 언제나 "없음"이다.
 *
 * <p>TTS 를 켜도 합성한 음성은 아이에게 가지 않는다. 음성을 실제로 들려주려면 인증된 아이만 받을 수 있는 임시 음성 주소를 만드는 구현으로 바꾼다(보관 기간·전달 정책
 * 결정 필요).
 */
public final class TextOnlyRoleplaySpeechDelivery implements RoleplaySpeechDelivery {
    @Override
    public Optional<String> publish(UUID approvedCandidateId, SynthesizedSpeech speech) {
        return Optional.empty();
    }
}
