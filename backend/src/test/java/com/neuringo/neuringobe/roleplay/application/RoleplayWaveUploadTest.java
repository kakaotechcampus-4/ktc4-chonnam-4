package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory.Reason;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory.Rejected;
import com.neuringo.neuringobe.roleplay.service.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoleplayWaveUploadTest {
    @TempDir Path directory;
    private final AiTraceContext trace =
            new AiTraceContext(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    null);

    private RoleplayUploadSpoolFactory factory() {
        return new RoleplayUploadSpoolFactory(
                directory, new RoleplayUploadPolicy(2_097_152, Set.of(AudioFormat.WAV)));
    }

    @ParameterizedTest
    @CsvSource({"1,8", "1,16", "1,24", "1,32", "3,32", "3,64"})
    void acceptsUncompressedFrameFormatsAndDeletesTheOwnedFileOnClose(int encoding, int bits)
            throws Exception {
        try (var audio =
                factory()
                        .create(
                                new ByteArrayInputStream(wave(encoding, bits, 8000)),
                                "audio/wav",
                                trace,
                                RoleplayTurnDeadline.start())) {
            assertThat(
                            RoleplayVoiceInput.prepare(audio, trace, RoleplayTurnDeadline.start())
                                    .request()
                                    .format())
                    .isEqualTo(AudioFormat.WAV);
            assertFiles(1);
        }
        assertFiles(0);
    }

    @Test
    void allowsExactlyThirtySecondsAndRejectsOneFrameOverWithoutRounding() throws Exception {
        try (var audio =
                factory()
                        .create(
                                new ByteArrayInputStream(wave(1, 16, 240000)),
                                "audio/wav",
                                trace,
                                RoleplayTurnDeadline.start())) {
            assertFiles(1);
        }
        assertFiles(0);
        rejects(wave(1, 16, 240001), Reason.TOO_LONG);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "4,4", "8,0", "16,-1", "22,0", "24,0", "28,1", "32,0", "40,-1"})
    void rejectsContradictoryHeadersAndOutOfBoundsChunks(int offset, int value) throws Exception {
        var bytes = wave(1, 16, 8000);
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (offset == 22 || offset == 32) header.putShort(offset, (short) value);
        else header.putInt(offset, value);
        rejects(bytes, Reason.INVALID_AUDIO);
    }

    @Test
    void rejectsFakeMimeTruncationEmptyFramesAndCompressedWave() throws Exception {
        rejects(new byte[] {1, 2, 3, 4}, Reason.INVALID_AUDIO);
        rejects(Arrays.copyOf(wave(1, 16, 8000), 43), Reason.INVALID_AUDIO);
        rejects(wave(1, 16, 0), Reason.INVALID_AUDIO);
        rejects(wave(2, 16, 8000), Reason.UNSUPPORTED_FORMAT);
        var incomplete = wave(1, 8, 1);
        ByteBuffer.wrap(incomplete)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(34, (short) 16)
                .putShort(32, (short) 2)
                .putInt(28, 16000);
        rejects(incomplete, Reason.INVALID_AUDIO);
    }

    @Test
    void handlesWordPaddingAndRejectsDuplicateData() throws Exception {
        var original = wave(1, 8, 1);
        var padded = ByteBuffer.allocate(original.length + 10).order(ByteOrder.LITTLE_ENDIAN);
        padded.put(original, 0, 36)
                .putInt(0x4b4e554a)
                .putInt(1)
                .put((byte) 0)
                .put((byte) 0)
                .put(original, 36, original.length - 36)
                .putInt(4, padded.capacity() - 8);
        try (var audio =
                factory()
                        .create(
                                new ByteArrayInputStream(padded.array()),
                                "audio/wav",
                                trace,
                                RoleplayTurnDeadline.start())) {
            assertFiles(1);
        }
        assertFiles(0);
        var duplicate = ByteBuffer.allocate(original.length + 10).order(ByteOrder.LITTLE_ENDIAN);
        duplicate
                .put(original)
                .putInt(0x61746164)
                .putInt(1)
                .putShort((short) 0)
                .putInt(4, duplicate.capacity() - 8);
        rejects(duplicate.array(), Reason.INVALID_AUDIO);
    }

    @Test
    void rejectedUploadCountsEligibleFailuresWithoutStartingTheLearningPipeline() throws Exception {
        var scopes = mock(RoleplayRequestScopeService.class);
        var scope = mock(RoleplayRequestScopeService.Scope.class);
        var turns = mock(RoleplayScopedVoiceTurnExecutor.class);
        when(scopes.loadForChildSession(isNull(), eq(trace.sessionId()))).thenReturn(scope);
        when(scope.trace(any(), any())).thenReturn(trace);
        when(scope.childId()).thenReturn(trace.childId());
        when(scope.activityId()).thenReturn(trace.activityId());
        when(scope.sessionId()).thenReturn(trace.sessionId());
        when(scope.scenarioId()).thenReturn(trace.scenarioId());
        when(scope.scenarioVersion()).thenReturn(trace.scenarioVersion());
        var outcomes = new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of()));
        when(turns.executeUploadFailure(any(), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(
                        new RoleplayCheckpointedTurnExecutor.InputRecovery(
                                outcomes.inputRecovery(), false, false));
        var service =
                new RoleplayVoiceInputService(
                        scopes,
                        factory(),
                        turns,
                        new RoleplayTurnResponseMapper((id, speech) -> Optional.empty()),
                        outcomes,
                        (recoveryKey, recoveryDeadline) -> RoleplayVoiceFailureProgress.initial());
        for (var bytes : List.of(new byte[] {1}, wave(1, 16, 240001), wave(2, 16, 8000))) {
            var response =
                    service.submitVoice(
                            null,
                            trace.sessionId(),
                            UUID.randomUUID(),
                            new ByteArrayInputStream(bytes),
                            "audio/wav",
                            RoleplayTurnDeadline.start());
            assertThat(response.status())
                    .isEqualTo(
                            com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryStatus
                                    .REINPUT_REQUIRED);
            assertThat(response.action())
                    .isEqualTo(
                            com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryAction
                                    .REQUEST_NEW_INPUT);
            assertThat(response.messages())
                    .singleElement()
                    .satisfies(message -> assertThat(message.text()).isEqualTo("다시 한 번만 말해줄래?"));
            assertThat(response.acceptedInput()).isNull();
            assertThat(response.checkpointVersion()).isNull();
            assertFiles(0);
        }
        verify(turns, times(3))
                .executeUploadFailure(any(), any(), any(), any(), anyString(), any(), any());
        verify(turns)
                .executeUploadFailure(
                        any(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        eq(RoleplayTurnResult.InputFailure.AUDIO_TOO_LONG),
                        any());
        verify(turns, never()).executeVoice(any(), any(), any(), any(), any(), any());
    }

    private void rejects(byte[] bytes, Reason reason) throws Exception {
        var source =
                new ByteArrayInputStream(bytes) {
                    boolean closed;

                    @Override
                    public void close() {
                        closed = true;
                    }
                };
        assertThatThrownBy(
                        () ->
                                factory()
                                        .create(
                                                source,
                                                "audio/wav",
                                                trace,
                                                RoleplayTurnDeadline.start()))
                .isInstanceOfSatisfying(
                        Rejected.class, ex -> assertThat(ex.reason()).isEqualTo(reason));
        assertThat(source.closed).isTrue();
        assertFiles(0);
    }

    private void assertFiles(int count) throws Exception {
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(count);
        }
    }

    private byte[] wave(int encoding, int bits, int frames) {
        int alignment = bits / 8;
        int data = alignment * frames;
        var bytes = ByteBuffer.allocate(44 + data + (data & 1)).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46464952)
                .putInt(bytes.capacity() - 8)
                .putInt(0x45564157)
                .putInt(0x20746d66)
                .putInt(16)
                .putShort((short) encoding)
                .putShort((short) 1)
                .putInt(8000)
                .putInt(8000 * alignment)
                .putShort((short) alignment)
                .putShort((short) bits)
                .putInt(0x61746164)
                .putInt(data);
        return bytes.array();
    }
}
