package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.*;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.infrastructure.input.*;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FfmpegRoleplayAudioInspectorTest {
    @TempDir Path directory;
    private Path file;
    private String probe;

    @BeforeEach
    void setup() throws Exception {
        file = Files.write(directory.resolve("owned.ogg"), new byte[] {1});
        probe =
                script(
                        "probe",
                        "printf '%s' '{\"format\":{\"format_name\":\"ogg\"},\"streams\":[{\"codec_type\":\"audio\",\"codec_name\":\"opus\",\"sample_rate\":\"8000\"}]}'");
    }

    @Test
    void missingToolsFailClosedAsInfrastructureFailure() {
        var inspector =
                new FfmpegRoleplayAudioInspector(
                        "/missing/ffmpeg", "/missing/ffprobe", Duration.ofSeconds(1));
        assertThatThrownBy(
                        () ->
                                inspector.requireValid(
                                        file, AudioFormat.OGG, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayAudioInspector.Unavailable.class);
    }

    @Test
    void boundedDecodedOutputAcceptsLimitAndRejectsOneExtraSample() throws Exception {
        var valid =
                inspector("dd if=/dev/zero bs=16000 count=30 2>/dev/null", Duration.ofSeconds(2));
        valid.requireValid(file, AudioFormat.OGG, RoleplayTurnDeadline.start());
        var longAudio =
                inspector(
                        "dd if=/dev/zero bs=16000 count=30 2>/dev/null; printf xx",
                        Duration.ofSeconds(2));
        assertThatThrownBy(
                        () ->
                                longAudio.requireValid(
                                        file, AudioFormat.OGG, RoleplayTurnDeadline.start()))
                .isInstanceOfSatisfying(
                        RoleplayUploadSpoolFactory.Rejected.class,
                        error ->
                                assertThat(error.reason())
                                        .isEqualTo(RoleplayUploadSpoolFactory.Reason.TOO_LONG));
    }

    @Test
    void decoderFailureAndEmptyOutputAreRejectedWithoutLeakingDiagnostics() throws Exception {
        for (var body : new String[] {"printf private-audio-diagnostic >&2; exit 1", "exit 0"}) {
            var inspector = inspector(body, Duration.ofSeconds(2));
            assertThatThrownBy(
                            () ->
                                    inspector.requireValid(
                                            file, AudioFormat.OGG, RoleplayTurnDeadline.start()))
                    .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class)
                    .hasMessageNotContaining("private-audio");
        }
    }

    @Test
    void localTimeoutAndSharedDeadlineStopTheProcess() throws Exception {
        var pid = directory.resolve("pid");
        String body = "echo $$ > '" + pid + "'; sleep 30";
        var limited = inspector(body, Duration.ofMillis(200));
        assertThatThrownBy(
                        () ->
                                limited.requireValid(
                                        file, AudioFormat.OGG, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayAudioInspector.Unavailable.class);
        if (Files.exists(pid)) {
            var handle = ProcessHandle.of(Long.parseLong(Files.readString(pid).trim()));
            if (handle.isPresent())
                handle.get().onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        var shared = inspector(body, Duration.ofSeconds(10));
        assertThatThrownBy(
                        () ->
                                shared.requireValid(
                                        file,
                                        AudioFormat.OGG,
                                        RoleplayTurnDeadline.start(Duration.ofMillis(100))))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
    }

    @Test
    void oversizedOrMalformedProbeOutputCannotReachTheDecoder() throws Exception {
        var marker = directory.resolve("decoded");
        String decoder = script("unreachable", "touch '" + marker + "'");
        for (var body :
                new String[] {"printf invalid", "dd if=/dev/zero bs=17000 count=1 2>/dev/null"}) {
            var inspector =
                    new FfmpegRoleplayAudioInspector(
                            decoder, script("invalid-probe", body), Duration.ofSeconds(2));
            assertThatThrownBy(
                            () ->
                                    inspector.requireValid(
                                            file, AudioFormat.OGG, RoleplayTurnDeadline.start()))
                    .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
        }
        assertThat(marker).doesNotExist();
    }

    @Test
    void concurrencyLimitRejectsAnotherInspectionWithoutWaiting() throws Exception {
        var marker = directory.resolve("started");
        var executable = script("limited-decoder", "touch '" + marker + "'; sleep 30");
        var limited = new FfmpegRoleplayAudioInspector(executable, probe, Duration.ofSeconds(5), 1);
        var firstDeadline = RoleplayTurnDeadline.start();
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var running =
                worker.submit(
                        () -> {
                            try {
                                limited.requireValid(file, AudioFormat.OGG, firstDeadline);
                            } catch (Exception expectedCancellation) {
                            }
                        });
        try {
            long stop = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(marker) && System.nanoTime() < stop) Thread.sleep(10);
            assertThat(marker).exists();
            assertThatThrownBy(
                            () ->
                                    limited.requireValid(
                                            file, AudioFormat.OGG, RoleplayTurnDeadline.start()))
                    .isInstanceOf(RoleplayAudioInspector.Unavailable.class);
        } finally {
            firstDeadline.cancel();
            running.get(2, java.util.concurrent.TimeUnit.SECONDS);
            worker.shutdownNow();
        }
    }

    private FfmpegRoleplayAudioInspector inspector(String body, Duration timeout) throws Exception {
        return new FfmpegRoleplayAudioInspector(script("decoder", body), probe, timeout);
    }

    private String script(String name, String body) throws Exception {
        var path = Files.writeString(directory.resolve(name), "#!/bin/sh\n" + body + "\n");
        assertThat(path.toFile().setExecutable(true, true)).isTrue();
        return path.toString();
    }
}
