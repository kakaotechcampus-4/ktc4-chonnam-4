package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

/** TTS 결과 음성. 아동에게 재생한 뒤 저장하지 않는다. */
public record SynthesizedSpeech(
        byte[] audio, AudioFormat format, String provider, String model, String voiceId) {

    public SynthesizedSpeech {
        Objects.requireNonNull(audio, "audio must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (audio.length == 0) {
            throw new IllegalArgumentException("audio must not be empty");
        }
        audio = audio.clone();
    }

    @Override
    public byte[] audio() {
        return audio.clone();
    }

    @Override
    public String toString() {
        return "SynthesizedSpeech[format="
                + format
                + ", bytes="
                + audio.length
                + ", provider="
                + provider
                + ", model="
                + model
                + ", voiceId="
                + voiceId
                + "]";
    }
}
