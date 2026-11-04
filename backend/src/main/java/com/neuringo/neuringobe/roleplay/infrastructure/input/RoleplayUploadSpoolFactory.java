package com.neuringo.neuringobe.roleplay.infrastructure.input;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.infrastructure.input.TemporaryRoleplayAudioFile;
import com.neuringo.neuringobe.roleplay.application.RoleplayAudioInspector;
import com.neuringo.neuringobe.roleplay.application.RoleplayUploadPolicy;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** 소유 디렉터리에 크기를 제한해 업로드하고 실제 형식·프레임 수를 확인한다. */
public final class RoleplayUploadSpoolFactory {
    private final Path directory;
    private final RoleplayUploadPolicy policy;
    private final RoleplayAudioInspector inspector;

    public RoleplayUploadSpoolFactory(Path directory, RoleplayUploadPolicy policy) {
        this(
                directory,
                policy,
                new FfmpegRoleplayAudioInspector(
                        "ffmpeg", "ffprobe", java.time.Duration.ofSeconds(10)));
    }

    public RoleplayUploadSpoolFactory(
            Path directory, RoleplayUploadPolicy policy, RoleplayAudioInspector inspector) {
        this.directory = Objects.requireNonNull(directory);
        this.inspector = Objects.requireNonNull(inspector);
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
                var format = formatOrNull(contentType);
                boolean supported = format != null && policy.allowedFormats().contains(format);
                var digest = RoleplayVoiceInput.newFingerprintDigest(format, contentType);
                if (supported)
                    file =
                            Files.createTempFile(
                                    directory, "roleplay-upload-", "." + format.fileExtension());
                long total = 0;
                try (var output =
                        supported
                                ? Files.newOutputStream(file, LinkOption.NOFOLLOW_LINKS)
                                : OutputStream.nullOutputStream()) {
                    while (true) {
                        deadline.requireActive();
                        long remaining = policy.maximumFingerprintBytes() - total;
                        int length = remaining == 0 ? 1 : (int) Math.min(buffer.length, remaining);
                        int read = source.read(buffer, 0, length);
                        deadline.requireActive();
                        if (read < 0) break;
                        if (read == 0) continue;
                        if (read > remaining) throw new Rejected(Reason.TOO_LARGE);
                        digest.update(buffer, 0, read);
                        total += read;
                        if (total <= policy.maximumBytes()) output.write(buffer, 0, read);
                    }
                }
                var fingerprint = HexFormat.of().formatHex(digest.digest());
                if (total > policy.maximumBytes())
                    throw new Rejected(Reason.TOO_LARGE, fingerprint);
                if (!supported) throw new Rejected(Reason.UNSUPPORTED_FORMAT, fingerprint);
                if (total == 0) throw new Rejected(Reason.EMPTY, fingerprint);
                deadline.requireActive();
                try {
                    if (format == AudioFormat.WAV)
                        RoleplayWaveValidator.requireValid(file, deadline);
                    else inspector.requireValid(file, format, deadline);
                } catch (Rejected rejected) {
                    throw rejected.withFingerprint(fingerprint);
                } catch (RoleplayAudioInspector.Unavailable unavailable) {
                    throw unavailable.withFingerprint(fingerprint);
                }
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

    private AudioFormat formatOrNull(String contentType) {
        if (contentType == null) return null;
        try {
            return AudioFormat.fromMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public enum Reason {
        EMPTY,
        TOO_LARGE,
        UNSUPPORTED_FORMAT,
        INVALID_AUDIO,
        TOO_LONG
    }

    public static final class Rejected extends IllegalArgumentException {
        private final Reason reason;
        private final String inputFingerprint;

        public Rejected(Reason reason) {
            this(reason, null);
        }

        Rejected(Reason reason, String fingerprint) {
            super("Voice upload rejected: " + reason);
            this.reason = reason;
            if (fingerprint != null && !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("음성 입력 지문이 올바르지 않습니다.");
            this.inputFingerprint = fingerprint;
        }

        Rejected withFingerprint(String fingerprint) {
            var copy = new Rejected(reason, Objects.requireNonNull(fingerprint));
            for (var suppressed : getSuppressed()) copy.addSuppressed(suppressed);
            return copy;
        }

        public String inputFingerprint() {
            return inputFingerprint;
        }

        public Reason reason() {
            return reason;
        }
    }
}
