package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoleplayVoiceInputTest {
    private final AiTraceContext trace =
            new AiTraceContext(
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
    private final AtomicInteger reads = new AtomicInteger();

    @Test
    void hasStableVersionedDigestAndCapturesInputOnce() {
        var input = prepare(trace, new byte[] {1, 2, 3}, AudioFormat.WEBM);
        assertThat(input.fingerprint())
                .isEqualTo("048f2ad0aaa426bb9e231b456b6a9477174b475187424876ca84f3f3fc252058");
        assertThat(input.request().audio()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        input.request();
        input.request();
        assertThat(reads).hasValue(1);
        assertThat(input.toString())
                .doesNotContain(input.fingerprint(), trace.requestId().toString());
    }

    @Test
    void retriesWithNewTraceIdsProduceTheSamePayloadFingerprint() {
        var other =
                new AiTraceContext(
                        UUID.randomUUID(),
                        trace.activityId(),
                        trace.classId(),
                        trace.childId(),
                        trace.scenarioId(),
                        1,
                        trace.sessionId(),
                        UUID.randomUUID(),
                        null,
                        null);
        assertThat(prepare(trace, new byte[] {1, 2, 3}, AudioFormat.WEBM).fingerprint())
                .isEqualTo(prepare(other, new byte[] {1, 2, 3}, AudioFormat.WEBM).fingerprint());
    }

    @Test
    void differentBytesOrCanonicalFormatChangeFingerprint() {
        var original = prepare(trace, new byte[] {1, 2, 3}, AudioFormat.WEBM).fingerprint();
        assertThat(prepare(trace, new byte[] {1, 2, 4}, AudioFormat.WEBM).fingerprint())
                .isNotEqualTo(original);
        assertThat(prepare(trace, new byte[] {1, 2, 3}, AudioFormat.OGG).fingerprint())
                .isNotEqualTo(original);
    }

    @Test
    void mimeAliasesResolveToSameCanonicalFormat() {
        assertThat(
                        prepare(
                                        trace,
                                        new byte[] {1, 2, 3},
                                        AudioFormat.fromMediaType("video/webm"))
                                .fingerprint())
                .isEqualTo(
                        prepare(
                                        trace,
                                        new byte[] {1, 2, 3},
                                        AudioFormat.fromMediaType("audio/webm;codecs=opus"))
                                .fingerprint());
    }

    @Test
    void snapshotIsUnaffectedByMutationOfOriginalOrReturnedBytes() {
        var bytes = new byte[] {1, 2, 3};
        var input = prepare(trace, bytes, AudioFormat.WEBM);
        bytes[0] = 9;
        var returned = input.request().audio();
        returned[1] = 9;
        assertThat(input.request().audio()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(input.fingerprint())
                .isEqualTo("048f2ad0aaa426bb9e231b456b6a9477174b475187424876ca84f3f3fc252058");
    }

    @Test
    void rejectsWrongTraceAndNonInitialAttempt() {
        var audio = new SpeechTranscriptionRequest(trace, 1, new byte[] {1}, AudioFormat.WEBM);
        var wrong =
                new AiTraceContext(
                        UUID.randomUUID(),
                        trace.activityId(),
                        trace.classId(),
                        trace.childId(),
                        trace.scenarioId(),
                        1,
                        trace.sessionId(),
                        trace.turnId(),
                        null,
                        null);
        assertThatThrownBy(
                        () ->
                                RoleplayVoiceInput.prepare(
                                        resource(audio), wrong, RoleplayTurnDeadline.start()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RoleplayVoiceInput.prepare(
                                        resource(
                                                new SpeechTranscriptionRequest(
                                                        trace,
                                                        2,
                                                        new byte[] {1},
                                                        AudioFormat.WEBM)),
                                        trace,
                                        RoleplayTurnDeadline.start()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredBudgetNeverReadsUpload() {
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        assertThatThrownBy(
                        () ->
                                RoleplayVoiceInput.prepare(
                                        resource(
                                                new SpeechTranscriptionRequest(
                                                        trace,
                                                        1,
                                                        new byte[] {1},
                                                        AudioFormat.WEBM)),
                                        trace,
                                        deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertThat(reads).hasValue(0);
    }

    private RoleplayVoiceInput prepare(AiTraceContext trace, byte[] bytes, AudioFormat format) {
        return RoleplayVoiceInput.prepare(
                resource(new SpeechTranscriptionRequest(trace, 1, bytes, format)),
                trace,
                RoleplayTurnDeadline.start());
    }

    private RoleplayAudioResource resource(SpeechTranscriptionRequest request) {
        return new RoleplayAudioResource() {
            public SpeechTranscriptionRequest request() {
                reads.incrementAndGet();
                return request;
            }

            public void close() {
                throw new AssertionError("Preparation does not own upload lifecycle");
            }
        };
    }
}
