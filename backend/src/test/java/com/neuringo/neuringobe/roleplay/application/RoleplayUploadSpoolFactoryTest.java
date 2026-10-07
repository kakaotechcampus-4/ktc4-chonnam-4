package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayUploadSpoolFactoryTest {
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
                directory, new RoleplayUploadPolicy(4, Set.of(AudioFormat.WEBM)));
    }

    @Test
    void exactLimitPreservesBytesAndTransfersFileOwnershipUntilClose() throws Exception {
        var source = new Source(new byte[] {1, 2, 3, 4});
        var resource =
                factory()
                        .create(
                                source,
                                "audio/webm;codecs=opus",
                                trace,
                                RoleplayTurnDeadline.start());
        assertThat(source.closed).isTrue();
        assertThat(files()).isEqualTo(1);
        try (resource) {
            var input = RoleplayVoiceInput.prepare(resource, trace, RoleplayTurnDeadline.start());
            assertThat(input.request().audio())
                    .containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 4);
            assertThat(input.request().format()).isEqualTo(AudioFormat.WEBM);
        }
        assertThat(files()).isZero();
    }

    @Test
    void emptyAndOneByteTooLargeUploadsAreRejectedAndRemoved() throws Exception {
        for (var bytes : new byte[][] {new byte[0], new byte[5]}) {
            var source = new Source(bytes);
            assertThatThrownBy(
                            () ->
                                    factory()
                                            .create(
                                                    source,
                                                    "audio/webm",
                                                    trace,
                                                    RoleplayTurnDeadline.start()))
                    .isInstanceOfSatisfying(
                            RoleplayUploadSpoolFactory.Rejected.class,
                            e ->
                                    assertThat(e.reason())
                                            .isEqualTo(
                                                    bytes.length == 0
                                                            ? RoleplayUploadSpoolFactory.Reason
                                                                    .EMPTY
                                                            : RoleplayUploadSpoolFactory.Reason
                                                                    .TOO_LARGE));
            assertThat(source.closed).isTrue();
            assertThat(files()).isZero();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"text/plain", "audio/ogg", "application/octet-stream"})
    void missingUnknownOrDisallowedMimeClosesSourceWithoutCreatingFile(String mime)
            throws Exception {
        var source = new Source(new byte[] {1});
        assertThatThrownBy(
                        () -> factory().create(source, mime, trace, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
        assertThat(source.closed).isTrue();
        assertThat(source.reads).isZero();
        assertThat(files()).isZero();
    }

    @Test
    void readFailureAfterPartialWriteDeletesTemporaryFile() throws Exception {
        var source =
                new Source(new byte[] {1, 2}) {
                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        if (reads > 0)
                            throw new java.io.UncheckedIOException(
                                    new IOException("synthetic read failure"));
                        return super.read(b, off, 1);
                    }
                };
        assertThatThrownBy(
                        () ->
                                factory()
                                        .create(
                                                source,
                                                "audio/webm",
                                                trace,
                                                RoleplayTurnDeadline.start()))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(source.closed).isTrue();
        assertThat(files()).isZero();
    }

    @Test
    void streamCloseFailureDoesNotTransferOrOrphanAFile() throws Exception {
        var source =
                new Source(new byte[] {1}) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        throw new IOException("synthetic close failure");
                    }
                };
        assertThatThrownBy(
                        () ->
                                factory()
                                        .create(
                                                source,
                                                "audio/webm",
                                                trace,
                                                RoleplayTurnDeadline.start()))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(source.closed).isTrue();
        assertThat(files()).isZero();
    }

    @Test
    void expiredBudgetClosesSourceWithoutReadOrFile() throws Exception {
        var source = new Source(new byte[] {1});
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        assertThatThrownBy(() -> factory().create(source, "audio/webm", trace, deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertThat(source.closed).isTrue();
        assertThat(source.reads).isZero();
        assertThat(files()).isZero();
    }

    @Test
    void cancellationDuringReadDiscardsTheTemporaryUpload() throws Exception {
        var deadline = RoleplayTurnDeadline.start();
        var source =
                new Source(new byte[] {1}) {
                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        int read = super.read(b, off, len);
                        deadline.cancel();
                        return read;
                    }
                };
        assertThatThrownBy(() -> factory().create(source, "audio/webm", trace, deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertThat(source.closed).isTrue();
        assertThat(files()).isZero();
    }

    @Test
    void actualReadBoundDoesNotTrustClientSizeMetadata() throws Exception {
        var source = new Source(new byte[1000]);
        assertThatThrownBy(
                        () ->
                                factory()
                                        .create(
                                                source,
                                                "audio/webm",
                                                trace,
                                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
        assertThat(source.consumed).isEqualTo(5);
        assertThat(files()).isZero();
    }

    @Test
    void policyHasNoImplicitFormatsOrLimitBeyondExistingSpeechCapacity() {
        assertThatThrownBy(() -> new RoleplayUploadPolicy(0, Set.of(AudioFormat.WEBM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RoleplayUploadPolicy(
                                        SpeechTranscriptionRequest.MAX_AUDIO_BYTES + 1,
                                        Set.of(AudioFormat.WEBM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoleplayUploadPolicy(4, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private long files() throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private static class Source extends ByteArrayInputStream {
        boolean closed;
        int reads;
        int consumed;

        Source(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            reads++;
            int n = super.read(b, off, len);
            if (n > 0) consumed += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
