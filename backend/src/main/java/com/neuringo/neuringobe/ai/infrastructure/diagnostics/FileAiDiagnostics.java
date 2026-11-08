package com.neuringo.neuringobe.ai.infrastructure.diagnostics;

import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** 제한된 Dev 실험의 원문만 비공개 파일에 기록한다. 저장 실패는 사용자 요청으로 전파하지 않는다. */
public final class FileAiDiagnostics implements AiDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(FileAiDiagnostics.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path root;
    private final long started;
    private final long windowNanos;
    private final int maxRequests;
    private final long maxBytes;
    private final int maxEventBytes;
    private final LongSupplier clock;
    private final Map<UUID, Capture> active = new HashMap<>();
    private final Set<UUID> admitted = new HashSet<>();
    private final Set<String> reported = new HashSet<>();
    private long bytes;
    private boolean disabled;

    public FileAiDiagnostics(
            Path root, Duration window, int maxRequests, long maxBytes, int maxEventBytes) {
        this(root, window, maxRequests, maxBytes, maxEventBytes, System::nanoTime);
    }

    public FileAiDiagnostics(
            Path root,
            Duration window,
            int maxRequests,
            long maxBytes,
            int maxEventBytes,
            LongSupplier clock) {
        if (window.isZero()
                || window.isNegative()
                || maxRequests < 1
                || maxBytes < 1
                || maxEventBytes < 1) throw new IllegalArgumentException("진단 수집 제한은 양수여야 합니다.");
        this.root = root.toAbsolutePath().normalize();
        this.windowNanos = window.toNanos();
        this.maxRequests = maxRequests;
        this.maxBytes = maxBytes;
        this.maxEventBytes = maxEventBytes;
        this.clock = clock;
        started = clock.getAsLong();
        try {
            if (Files.isSymbolicLink(this.root)) throw new IOException();
            Files.createDirectories(
                    this.root,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwx------"));
            // 남아 있는 파일도 총 용량에 포함하며 다른 실행의 원문을 덮어쓰지 않는다.
            try (var paths = Files.walk(this.root)) {
                for (var path : paths.toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException();
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        bytes = Math.addExact(bytes, Files.size(path));
                }
            }
        } catch (IOException | RuntimeException failure) {
            disabled = true;
            skipped("STORAGE_UNAVAILABLE");
        }
    }

    @Override
    public synchronized void begin(UUID requestId) {
        if (!available() || admitted.contains(requestId)) return;
        if (admitted.size() >= maxRequests) {
            skipped("REQUEST_LIMIT");
            return;
        }
        admitted.add(requestId);
        Path directory = root.resolve(requestId.toString());
        try {
            Files.createDirectory(
                    directory,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            Path file = directory.resolve("diagnostics.jsonl");
            Files.createFile(
                    file,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            active.put(requestId, new Capture(file));
            log.info("AI_DIAGNOSTICS state=STARTED request={}", requestId);
        } catch (IOException | RuntimeException failure) {
            skipped("STORAGE_UNAVAILABLE");
        }
    }

    @Override
    public synchronized boolean active(UUID requestId) {
        return available() && active.containsKey(requestId);
    }

    @Override
    public synchronized void record(UUID requestId, String event, Map<String, Object> data) {
        if (!active(requestId)) return;
        Capture capture = active.get(requestId);
        try {
            var entry =
                    AiDiagnostics.fields(
                            "request_id",
                            requestId,
                            "sequence",
                            ++capture.sequence,
                            "event",
                            event,
                            "capture_elapsed_ms",
                            (clock.getAsLong() - started) / 1_000_000,
                            "data",
                            data);
            byte[] line = (JSON.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            if (line.length > maxEventBytes) {
                skipped("EVENT_LIMIT");
                line =
                        (JSON.writeValueAsString(
                                                AiDiagnostics.fields(
                                                        "request_id",
                                                        requestId,
                                                        "sequence",
                                                        capture.sequence,
                                                        "event",
                                                        "CAPTURE_SKIPPED",
                                                        "original_event",
                                                        event,
                                                        "reason",
                                                        "EVENT_LIMIT",
                                                        "original_bytes",
                                                        line.length))
                                        + "\n")
                                .getBytes(StandardCharsets.UTF_8);
            }
            if (line.length > maxEventBytes || line.length > maxBytes - bytes) {
                skipped("TOTAL_LIMIT");
                return;
            }
            bytes += line.length;
            try (var channel =
                    Files.newByteChannel(
                            capture.file,
                            Set.of(
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.APPEND,
                                    LinkOption.NOFOLLOW_LINKS))) {
                var buffer = java.nio.ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        } catch (IOException | RuntimeException failure) {
            active.remove(requestId);
            skipped("STORAGE_UNAVAILABLE");
        }
    }

    @Override
    public synchronized void end(UUID requestId, Map<String, Object> data) {
        record(requestId, "HTTP_RESULT", data);
        active.remove(requestId);
    }

    private boolean available() {
        if (disabled) return false;
        if (clock.getAsLong() - started >= windowNanos) {
            skipped("WINDOW_EXPIRED");
            return false;
        }
        if (bytes >= maxBytes) {
            skipped("TOTAL_LIMIT");
            return false;
        }
        return true;
    }

    private void skipped(String reason) {
        if (reported.add(reason)) log.info("AI_DIAGNOSTICS state=SKIPPED reason={}", reason);
    }

    private static final class Capture {
        final Path file;
        long sequence;

        Capture(Path file) {
            this.file = file;
        }
    }
}
