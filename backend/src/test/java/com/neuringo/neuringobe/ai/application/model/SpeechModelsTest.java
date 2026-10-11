package com.neuringo.neuringobe.ai.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SpeechModelsTest {

    @Test
    void parsesBrowserRecorderMediaTypes() {
        assertThat(AudioFormat.fromMediaType("audio/webm;codecs=opus")).isEqualTo(AudioFormat.WEBM);
        assertThat(AudioFormat.fromMediaType("AUDIO/MPEG")).isEqualTo(AudioFormat.MP3);
        assertThat(AudioFormat.fromMediaType("audio/x-wav")).isEqualTo(AudioFormat.WAV);
        assertThat(AudioFormat.fromMediaType("audio/mp4")).isEqualTo(AudioFormat.MP4);
        assertThatThrownBy(() -> AudioFormat.fromMediaType("image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transcriptionRequestNeedsSessionAndTurn() {
        AiTraceContext withoutTurn =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID(),
                        null,
                        null,
                        null);

        assertThatThrownBy(
                        () ->
                                new SpeechTranscriptionRequest(
                                        withoutTurn, 1, new byte[] {1}, AudioFormat.WEBM))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("turnId");
    }

    @Test
    void transcriptionRequestRejectsEmptyOrOversizedAudio() {
        assertThatThrownBy(
                        () ->
                                new SpeechTranscriptionRequest(
                                        trace(), 1, new byte[0], AudioFormat.WEBM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SpeechTranscriptionRequest(
                                        trace(),
                                        1,
                                        new byte[SpeechTranscriptionRequest.MAX_AUDIO_BYTES + 1],
                                        AudioFormat.WEBM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SpeechTranscriptionRequest(
                                        trace(), 0, new byte[] {1}, AudioFormat.WEBM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transcriptionRequestCopiesAudio() {
        byte[] audio = {1, 2, 3};
        SpeechTranscriptionRequest request =
                new SpeechTranscriptionRequest(trace(), 1, audio, AudioFormat.WEBM);

        audio[0] = 9;
        request.audio()[1] = 9;

        assertThat(request.audio()).containsExactly(1, 2, 3);
    }

    @Test
    void transcriptionDoesNotPrintUtterance() {
        SpeechTranscription transcription =
                new SpeechTranscription("내 이름은 민수야", 0.9, "openai", "m", null, null);

        assertThat(transcription.toString()).doesNotContain("민수");
        assertThat(transcription.hasSpeech()).isTrue();
        assertThat(new SpeechTranscription("  ", null, "openai", "m", null, null).hasSpeech())
                .isFalse();
        assertThatThrownBy(() -> new SpeechTranscription("a", 1.5, "openai", "m", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void synthesisRequestValidatesTextAndDefaults() {
        SpeechSynthesisRequest request =
                new SpeechSynthesisRequest(trace(), 1, "안녕 민수야", " ", null);

        assertThat(request.voiceId()).isNull();
        assertThat(request.emotion()).isEqualTo(SpeechEmotion.NORMAL);
        assertThat(request.toString()).doesNotContain("민수");
        assertThatThrownBy(() -> new SpeechSynthesisRequest(trace(), 1, " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SpeechSynthesisRequest(
                                        trace(),
                                        1,
                                        "가".repeat(SpeechSynthesisRequest.MAX_TEXT_LENGTH + 1),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = AiOperation.class,
            names = {"SPEECH_TRANSCRIPTION", "SPEECH_SYNTHESIS"})
    void llmRequestRejectsSpeechOperations(AiOperation operation) {
        assertThatThrownBy(
                        () ->
                                new LlmRequest(
                                        trace(),
                                        new AiAttemptContext(0, 0, 0),
                                        operation,
                                        1,
                                        "system",
                                        "user",
                                        "p1",
                                        "s1",
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiTraceContext trace() {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null);
    }
}
