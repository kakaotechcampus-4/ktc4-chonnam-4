package com.neuringo.neuringobe.roleplay.infrastructure.input;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 프로세스가 사용하는 임시 디렉터리와 소유권 잠금을 함께 보유한다. */
public final class RoleplayOwnedSpool implements AutoCloseable {
    static final String DIRECTORY_PREFIX = "roleplay-process-";
    static final String OWNER_FILE = ".owner.lock";
    static final String GUARD_FILE = ".roleplay-spool.guard";
    private static final Set<Path> LIVE_DIRECTORIES = new HashSet<>();
    private static final Map<Path, RoleplayOwnedSpool> PROCESS_OWNERS = new HashMap<>();

    private final Path directory;
    private final Path liveKey;
    private final FileChannel channel;
    private final FileLock lock;

    private RoleplayOwnedSpool(Path directory, Path liveKey, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.liveKey = liveKey;
        this.channel = channel;
        this.lock = lock;
    }

    public static synchronized RoleplayOwnedSpool create(Path parent) throws IOException {
        Objects.requireNonNull(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("소유 임시 디렉터리가 필요합니다.");
        try (var guard =
                        FileChannel.open(
                                parent.resolve(GUARD_FILE),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
                var ignored = guard.lock()) {
            cleanupAbandoned(parent);
            var owned = createOwned(parent);
            LIVE_DIRECTORIES.add(owned.liveKey);
            return owned;
        }
    }

    private static RoleplayOwnedSpool createOwned(Path parent) throws IOException {
        var directory = Files.createTempDirectory(parent, DIRECTORY_PREFIX);
        FileChannel channel = null;
        try {
            channel =
                    FileChannel.open(
                            directory.resolve(OWNER_FILE),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("임시 디렉터리 소유권을 확보하지 못했습니다.");
            return new RoleplayOwnedSpool(directory, directory.toRealPath(), channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (channel != null) Files.deleteIfExists(directory.resolve(OWNER_FILE));
                Files.deleteIfExists(directory);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static synchronized RoleplayOwnedSpool createForProcess(Path parent) throws IOException {
        var owned = create(parent);
        // 종료 유예가 끝나도 남은 작업이 있을 수 있어 잠금은 프로세스 종료까지 유지한다.
        PROCESS_OWNERS.put(owned.liveKey, owned);
        return owned;
    }

    private static void cleanupAbandoned(Path parent) throws IOException {
        try (var entries = Files.newDirectoryStream(parent, DIRECTORY_PREFIX + "*")) {
            for (var directory : entries) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                if (LIVE_DIRECTORIES.contains(directory.toRealPath())) continue;
                var marker = directory.resolve(OWNER_FILE);
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var owner =
                        FileChannel.open(
                                marker, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    FileLock candidate;
                    try {
                        candidate = owner.tryLock();
                    } catch (OverlappingFileLockException active) {
                        continue;
                    }
                    if (candidate == null) continue;
                    try (candidate) {
                        List<Path> files;
                        try (var contents = Files.list(directory)) {
                            files = contents.filter(path -> !path.equals(marker)).toList();
                        }
                        if (files.stream().anyMatch(path -> !isOwnedUpload(path))) continue;
                        for (var file : files) Files.delete(file);
                    }
                }
                Files.delete(marker);
                Files.delete(directory);
            }
        }
    }

    private static boolean isOwnedUpload(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        String name = path.getFileName().toString();
        return name.startsWith("roleplay-upload-")
                || (name.startsWith("upload_") && name.endsWith(".tmp"));
    }

    public Path directory() {
        if (!lock.isValid()) throw new IllegalStateException("임시 디렉터리 소유권이 종료되었습니다.");
        return directory;
    }

    @Override
    public void close() throws IOException {
        synchronized (RoleplayOwnedSpool.class) {
            if (!channel.isOpen()) return;
            if (PROCESS_OWNERS.containsKey(liveKey)) return;
            try {
                lock.release();
            } finally {
                try {
                    channel.close();
                } finally {
                    LIVE_DIRECTORIES.remove(liveKey);
                }
            }
        }
    }
}
