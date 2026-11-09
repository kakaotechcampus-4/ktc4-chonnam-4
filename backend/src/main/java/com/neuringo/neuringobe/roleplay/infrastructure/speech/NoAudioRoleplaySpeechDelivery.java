package com.neuringo.neuringobe.roleplay.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import java.util.Optional;
import java.util.UUID;

/** 음성 미제공을 명시적으로 선택한 환경에서 대사만 전달한다. */
public final class NoAudioRoleplaySpeechDelivery implements RoleplaySpeechDelivery {
    @Override
    public Optional<String> publish(UUID approvedCandidateId, SynthesizedSpeech speech) {
        return Optional.empty();
    }
}
