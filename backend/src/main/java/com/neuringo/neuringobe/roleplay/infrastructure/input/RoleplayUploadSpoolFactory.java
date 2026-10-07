package com.neuringo.neuringobe.roleplay.infrastructure.input;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.infrastructure.input.TemporaryRoleplayAudioFile;
import com.neuringo.neuringobe.roleplay.application.RoleplayUploadPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Trusted server directory only. Declared MIME validation, not container/decoder or duration
 * validation.
 */
public final class RoleplayUploadSpoolFactory {
    private final Path directory;
    private final RoleplayUploadPolicy policy;

    public RoleplayUploadSpoolFactory(Path directory, RoleplayUploadPolicy policy) {
        this.directory = Objects.requireNonNull(directory);
        this.policy = Objects.requireNonNull(policy);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Expected an owned upload directory");
    }

    /**
     * Owns/closes source on all exits; on success transfers disposable file ownership to caller. No
     * client filename/path accepted.
     */
    public RoleplayAudioResource create(
            InputStream source,
            String contentType,
            AiTraceContext trace,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(source);
        Path file = null;
        boolean transferred = false;
        Throwable failure = null;
        byte[] buffer = new byte[8192];
        try {
            RoleplayAudioResource result;
            try (source) {
                Objects.requireNonNull(deadline).requireActive();
                Objects.requireNonNull(trace);
                Objects.requireNonNull(trace.sessionId());
                Objects.requireNonNull(trace.turnId());
                var format = format(contentType);
                file =
                        Files.createTempFile(
                                directory, "roleplay-upload-", "." + format.fileExtension());
                int total = 0;
                try (var output = Files.newOutputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    while (true) {
                        deadline.requireActive();
                        int read =
                                source.read(
                                        buffer,
                                        0,
                                        Math.min(buffer.length, policy.maximumBytes() - total + 1));
                        deadline.requireActive();
                        if (read < 0) break;
                        if (read == 0) continue;
                        if (read > policy.maximumBytes() - total)
                            throw new Rejected(Reason.TOO_LARGE);
                        output.write(buffer, 0, read);
                        total += read;
                    }
                }
                if (total == 0) throw new Rejected(Reason.EMPTY);
                deadline.requireActive();
                result = new TemporaryRoleplayAudioFile(file, trace, format);
            }
            deadline.requireActive();
            transferred = true;
            return result;
        } catch (IOException ex) {
            var wrapped = new UncheckedIOException("Could not prepare temporary upload", ex);
            failure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            if (!transferred && file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else
                        throw new UncheckedIOException("Could not remove rejected upload", cleanup);
                }
            }
        }
    }

    private AudioFormat format(String contentType) {
        if (contentType == null) throw new Rejected(Reason.UNSUPPORTED_FORMAT);
        AudioFormat format;
        try {
            format = AudioFormat.fromMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            throw new Rejected(Reason.UNSUPPORTED_FORMAT);
        }
        if (!policy.allowedFormats().contains(format))
            throw new Rejected(Reason.UNSUPPORTED_FORMAT);
        return format;
    }

    public enum Reason {
        EMPTY,
        TOO_LARGE,
        UNSUPPORTED_FORMAT
    }

    public static final class Rejected extends IllegalArgumentException {
        private final Reason reason;

        private Rejected(Reason reason) {
            super("Voice upload rejected: " + reason);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
