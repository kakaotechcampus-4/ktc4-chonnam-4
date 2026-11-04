package com.neuringo.neuringobe.roleplay.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoleplayOwnedSpoolTest {
    @TempDir Path parent;

    @Test
    void liveOwnersUseDifferentDirectoriesAndHoldExclusiveLocks() throws Exception {
        try (var first = RoleplayOwnedSpool.create(parent);
                var second = RoleplayOwnedSpool.create(parent);
                var probe =
                        FileChannel.open(
                                first.directory().resolve(RoleplayOwnedSpool.OWNER_FILE),
                                StandardOpenOption.WRITE)) {
            assertThat(first.directory()).isNotEqualTo(second.directory());
            assertThat(first.directory().getParent()).isEqualTo(parent);
            assertThatThrownBy(probe::tryLock).isInstanceOf(OverlappingFileLockException.class);
        }
    }

    @Test
    void closedOwnerReleasesLockWithoutDeletingFiles() throws Exception {
        var owner = RoleplayOwnedSpool.create(parent);
        var directory = owner.directory();
        var audio = Files.writeString(directory.resolve("roleplay-upload-test.webm"), "sample");
        owner.close();
        assertThat(Files.exists(audio)).isTrue();
        assertThatThrownBy(owner::directory).isInstanceOf(IllegalStateException.class);
        try (var probe =
                        FileChannel.open(
                                directory.resolve(RoleplayOwnedSpool.OWNER_FILE),
                                StandardOpenOption.WRITE);
                var acquired = probe.tryLock()) {
            assertThat(acquired).isNotNull();
        }
    }

    @Test
    void rejectsMissingAndSymlinkedParent() throws Exception {
        assertThatThrownBy(() -> RoleplayOwnedSpool.create(parent.resolve("missing")))
                .isInstanceOf(IllegalArgumentException.class);
        var link = parent.resolve("link");
        Files.createSymbolicLink(link, parent);
        assertThatThrownBy(() -> RoleplayOwnedSpool.create(link))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reapsOnlyAbandonedOwnedUploadsAndKeepsLiveAndUnknownFiles() throws Exception {
        var abandoned = RoleplayOwnedSpool.create(parent);
        var abandonedDir = abandoned.directory();
        Files.writeString(abandonedDir.resolve("roleplay-upload-old.webm"), "audio");
        Files.writeString(abandonedDir.resolve("upload_old.tmp"), "multipart");
        abandoned.close();
        try (var live = RoleplayOwnedSpool.create(parent)) {
            assertThat(Files.exists(abandonedDir)).isFalse();
            var liveFile =
                    Files.writeString(
                            live.directory().resolve("roleplay-upload-live.wav"), "audio");
            var unknown = RoleplayOwnedSpool.create(parent);
            var unknownDir = unknown.directory();
            var unknownFile = Files.writeString(unknownDir.resolve("unrelated.txt"), "keep");
            unknown.close();
            try (var next = RoleplayOwnedSpool.create(parent)) {
                assertThat(Files.exists(liveFile)).isTrue();
                assertThat(Files.exists(unknownFile)).isTrue();
                assertThat(Files.exists(next.directory())).isTrue();
            }
        }
    }

    @Test
    void neverFollowsLinksInAbandonedDirectories() throws Exception {
        var external = Files.writeString(parent.resolve("external.txt"), "keep");
        var owner = RoleplayOwnedSpool.create(parent);
        var directory = owner.directory();
        Files.createSymbolicLink(directory.resolve("roleplay-upload-link.wav"), external);
        owner.close();
        try (var next = RoleplayOwnedSpool.create(parent)) {
            assertThat(Files.readString(external)).isEqualTo("keep");
            assertThat(Files.isSymbolicLink(directory.resolve("roleplay-upload-link.wav")))
                    .isTrue();
            assertThat(Files.exists(next.directory())).isTrue();
        }
    }

    @Test
    void killedProcessReleasesItsLockAndNextStartRemovesItsUploads() throws Exception {
        var classes =
                Path.of(
                        RoleplayOwnedSpool.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        var tests =
                Path.of(
                        RoleplayOwnedSpoolTest.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        var child =
                new ProcessBuilder(
                                javaExecutable.toString(),
                                "-cp",
                                classes + java.io.File.pathSeparator + tests,
                                LockChild.class.getName(),
                                parent.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            var reader =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(child.getInputStream()));
            var directory = Path.of(reader.readLine());
            var audio = Files.writeString(directory.resolve("roleplay-upload-crash.webm"), "audio");
            try (var live = RoleplayOwnedSpool.create(parent)) {
                assertThat(Files.exists(audio)).isTrue();
            }
            child.destroyForcibly();
            assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try (var next = RoleplayOwnedSpool.create(parent)) {
                assertThat(Files.exists(directory)).isFalse();
                assertThat(Files.exists(next.directory())).isTrue();
            }
        } finally {
            child.destroyForcibly();
        }
    }

    public static class LockChild {
        public static void main(String[] args) throws Exception {
            var owner = RoleplayOwnedSpool.createForProcess(Path.of(args[0]));
            owner.close();
            System.out.println(owner.directory());
            System.out.flush();
            System.in.read();
        }
    }
}
