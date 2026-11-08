package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.*;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.infrastructure.input.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@EnabledIfEnvironmentVariable(named = "ROLEPLAY_MEDIA_REAL_TOOLS", matches = "true")
class FfmpegRoleplayAudioInspectorRealTest {
    private Path directory;
    private final String ffmpeg = System.getenv("ROLEPLAY_MEDIA_TEST_FFMPEG");
    private final String ffprobe = System.getenv("ROLEPLAY_MEDIA_TEST_FFPROBE");
    private FfmpegRoleplayAudioInspector inspector;

    @BeforeEach
    void setup() throws Exception {
        directory =
                Files.createTempDirectory(
                        Path.of(System.getenv("ROLEPLAY_MEDIA_TEST_DIRECTORY")), "audio-");
        inspector = new FfmpegRoleplayAudioInspector(ffmpeg, ffprobe, Duration.ofSeconds(10));
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "WEBM,libopus,webm",
        "OGG,libopus,ogg",
        "OGG,libvorbis,ogg",
        "MP3,libmp3lame,mp3",
        "MP4,aac,m4a"
    })
    void acceptsActualEncodedAudio(AudioFormat format, String codec, String extension)
            throws Exception {
        var file = generate(codec, extension, "1", List.of());
        inspector.requireValid(file, format, RoleplayTurnDeadline.start());
    }

    @ParameterizedTest
    @CsvSource({"WEBM,libopus,webm", "OGG,libopus,ogg", "MP3,libmp3lame,mp3", "MP4,aac,m4a"})
    void verifiesExactlyThirtySecondsAndRejectsLongerDecodedAudio(
            AudioFormat format, String codec, String extension) throws Exception {
        inspector.requireValid(
                generate(codec, extension, "30", List.of()), format, RoleplayTurnDeadline.start());
        var tooLong = generate(codec, extension, "30.1", List.of());
        var spool = Files.createDirectory(directory.resolve("spool"));
        var factory =
                new RoleplayUploadSpoolFactory(
                        spool, new RoleplayUploadPolicy(2097152, Set.of(format)), inspector);
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null);
        assertThatThrownBy(
                        () ->
                                factory.create(
                                        Files.newInputStream(tooLong),
                                        format.mediaType(),
                                        trace,
                                        RoleplayTurnDeadline.start()))
                .isInstanceOfSatisfying(
                        RoleplayUploadSpoolFactory.Rejected.class,
                        error ->
                                assertThat(error.reason())
                                        .isEqualTo(RoleplayUploadSpoolFactory.Reason.TOO_LONG));
        try (var files = Files.list(spool)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void validatesBrowserStyleWebmWithoutDurationMetadata() throws Exception {
        inspector.requireValid(
                generate("libopus", "webm", "30", List.of("-live", "1")),
                AudioFormat.WEBM,
                RoleplayTurnDeadline.start());
    }

    @Test
    void rejectsMismatchedMimeTruncatedHeadersAndMatroska() throws Exception {
        var ogg = generate("libopus", "ogg", "1", List.of());
        assertThatThrownBy(
                        () ->
                                inspector.requireValid(
                                        ogg, AudioFormat.WEBM, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
        var corrupt = directory.resolve("corrupt.webm");
        Files.write(corrupt, Arrays.copyOf(Files.readAllBytes(ogg), 16));
        assertThatThrownBy(
                        () ->
                                inspector.requireValid(
                                        corrupt, AudioFormat.OGG, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
        var matroska = generate("libopus", "mkv", "1", List.of());
        assertThatThrownBy(
                        () ->
                                inspector.requireValid(
                                        matroska, AudioFormat.WEBM, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
    }

    @Test
    void rejectsVideoAndAudioTogether() throws Exception {
        var file = directory.resolve("video.mp4");
        command(
                List.of(
                        ffmpeg,
                        "-v",
                        "error",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=size=16x16:rate=1",
                        "-f",
                        "lavfi",
                        "-i",
                        "anullsrc=r=48000:cl=mono",
                        "-t",
                        "1",
                        "-c:v",
                        "mpeg4",
                        "-c:a",
                        "aac",
                        file.toString()));
        assertThatThrownBy(
                        () ->
                                inspector.requireValid(
                                        file, AudioFormat.MP4, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
    }

    private Path generate(String codec, String extension, String seconds, List<String> extra)
            throws Exception {
        var file = directory.resolve(UUID.randomUUID() + "." + extension);
        var args =
                new ArrayList<>(
                        List.of(
                                ffmpeg,
                                "-v",
                                "error",
                                "-f",
                                "lavfi",
                                "-i",
                                "anullsrc=r=48000:cl=mono",
                                "-t",
                                seconds,
                                "-c:a",
                                codec));
        args.addAll(extra);
        args.add(file.toString());
        command(args);
        return file;
    }

    private void command(List<String> args) throws Exception {
        var process =
                new ProcessBuilder(args)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            process.destroyForcibly();
        }
    }
}
