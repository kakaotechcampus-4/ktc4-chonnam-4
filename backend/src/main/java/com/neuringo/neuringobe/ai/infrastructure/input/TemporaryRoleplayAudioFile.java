package com.neuringo.neuringobe.ai.infrastructure.input;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Only use with an application-created upload file. Never use an arbitrary client-supplied path.
 */
public final class TemporaryRoleplayAudioFile implements RoleplayAudioResource {
    private final Path path;
    private final AiTraceContext trace;
    private final AudioFormat format;
    private final AtomicBoolean closed = new AtomicBoolean();

    public TemporaryRoleplayAudioFile(Path path, AiTraceContext trace, AudioFormat format) {
        this.path = Objects.requireNonNull(path);
        this.trace = Objects.requireNonNull(trace);
        this.format = Objects.requireNonNull(format);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Expected an owned regular upload file");
        }
    }

    @Override
    public SpeechTranscriptionRequest request() {
        requireOpen();
        byte[] bytes = null;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(SpeechTranscriptionRequest.MAX_AUDIO_BYTES + 1);
            requireOpen();
            return new SpeechTranscriptionRequest(trace, 1, bytes, format);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read temporary audio", failure);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not remove temporary audio", failure);
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Temporary audio is closed");
    }
}
