package com.neuringo.neuringobe.roleplay.infrastructure.input;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.application.RoleplayAudioInspector;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

/** 메타데이터의 길이를 신뢰하지 않고 원래 샘플레이트로 디코딩한 프레임을 센다. */
public final class FfmpegRoleplayAudioInspector implements RoleplayAudioInspector {
    private final String ffmpeg, ffprobe;
    private final long timeoutNanos;
    private final Semaphore slots;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String FORMATS = "matroska,webm,ogg,mp3,mov";

    public FfmpegRoleplayAudioInspector(String ffmpeg, String ffprobe, Duration timeout) {
        this(ffmpeg, ffprobe, timeout, 2);
    }

    public FfmpegRoleplayAudioInspector(
            String ffmpeg, String ffprobe, Duration timeout, int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("음성 검증 동시 실행 수는 양수여야 합니다.");
        slots = new Semaphore(concurrency);
        if (ffmpeg == null
                || ffmpeg.isBlank()
                || ffprobe == null
                || ffprobe.isBlank()
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(RoleplayTurnDeadline.MAX_DURATION) > 0)
            throw new IllegalArgumentException("음성 검증 실행 파일과 60초 이하 제한 시간이 필요합니다.");
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.timeoutNanos = timeout.toNanos();
    }

    @Override
    public void requireValid(Path file, AudioFormat format, RoleplayTurnDeadline deadline)
            throws IOException {
        deadline.requireActive();
        if (!slots.tryAcquire()) throw new RoleplayAudioInspector.Unavailable();
        try {
            inspect(file, format, deadline);
        } finally {
            slots.release();
        }
    }

    private void inspect(Path file, AudioFormat format, RoleplayTurnDeadline deadline)
            throws IOException {
        long started = System.nanoTime();
        var probe = new ArrayList<>(List.of(ffprobe, "-v", "error"));
        addInputOptions(probe);
        probe.addAll(
                List.of(
                        "-show_entries",
                        "format=format_name:stream=codec_type,codec_name,sample_rate,profile,time_base",
                        "-of",
                        "json",
                        "-i",
                        file.toAbsolutePath().toString()));
        var output = run(probe, 16384, true, started, deadline);
        int rate;
        boolean aac;
        try {
            var metadata = json.readTree(output.bytes());
            var streams = metadata.path("streams");
            if (!streams.isArray() || streams.size() != 1) reject();
            var stream = streams.get(0);
            if (!stream.path("codec_type").asString().equals("audio")) reject();
            String container = metadata.path("format").path("format_name").asString();
            String codec = stream.path("codec_name").asString();
            boolean matches =
                    switch (format) {
                        case WEBM ->
                                container.equals("matroska,webm")
                                        && Set.of("opus", "vorbis").contains(codec)
                                        && webmDocType(file);
                        case OGG ->
                                container.equals("ogg") && Set.of("opus", "vorbis").contains(codec);
                        case MP3 -> container.equals("mp3") && codec.equals("mp3");
                        case MP4 ->
                                container.equals("mov,mp4,m4a,3gp,3g2,mj2")
                                        && codec.equals("aac")
                                        && mp4Brand(file);
                        case WAV -> false;
                    };
            aac = format == AudioFormat.MP4;
            if (aac
                    && (!stream.path("profile").asString().equals("LC")
                            || !stream.path("time_base")
                                    .asString()
                                    .equals("1/" + stream.path("sample_rate").asString())))
                throw new RoleplayUploadSpoolFactory.Rejected(
                        RoleplayUploadSpoolFactory.Reason.UNSUPPORTED_FORMAT);
            if (!matches)
                throw new RoleplayUploadSpoolFactory.Rejected(
                        RoleplayUploadSpoolFactory.Reason.UNSUPPORTED_FORMAT);
            rate = Integer.parseInt(stream.path("sample_rate").asString());
            if (rate < 1 || rate > 192000)
                throw new RoleplayUploadSpoolFactory.Rejected(
                        RoleplayUploadSpoolFactory.Reason.UNSUPPORTED_FORMAT);
        } catch (RoleplayUploadSpoolFactory.Rejected rejected) {
            throw rejected;
        } catch (RuntimeException invalidMetadata) {
            reject();
            return;
        } finally {
            Arrays.fill(output.bytes(), (byte) 0);
        }
        long presentedSamples = aac ? aacPresentedSamples(file, rate, started, deadline) : -1;
        long maximumSamples = aac ? presentedSamples + 1023 : (long) rate * 30;
        var decode =
                new ArrayList<>(
                        List.of(
                                ffmpeg,
                                "-v",
                                "error",
                                "-nostdin",
                                "-xerror",
                                "-max_error_rate",
                                "0",
                                "-threads",
                                "1",
                                "-filter_threads",
                                "1",
                                "-err_detect",
                                "explode"));
        addInputOptions(decode);
        decode.addAll(
                List.of(
                        "-i",
                        file.toAbsolutePath().toString(),
                        "-map",
                        "0:a:0",
                        "-vn",
                        "-sn",
                        "-dn",
                        "-t",
                        "31",
                        "-ac",
                        "1",
                        "-ar",
                        Integer.toString(rate),
                        "-c:a",
                        "pcm_s16le",
                        "-f",
                        "s16le",
                        "pipe:1"));
        var decoded = run(decode, maximumSamples * 2, false, started, deadline);
        if (decoded.count() == 0
                || decoded.count() % 2 != 0
                || (aac && decoded.count() / 2 < presentedSamples)) reject();
        deadline.requireActive();
    }

    /** AAC-LC 마지막 프레임의 인코더 패딩은 패킷에 명시된 재생 구간과 대조한다. */
    private long aacPresentedSamples(
            Path file, int rate, long started, RoleplayTurnDeadline deadline) throws IOException {
        var command = new ArrayList<>(List.of(ffprobe, "-v", "error"));
        addInputOptions(command);
        command.addAll(
                List.of(
                        "-select_streams",
                        "a:0",
                        "-show_packets",
                        "-show_entries",
                        "packet=pts,duration",
                        "-of",
                        "json",
                        "-i",
                        file.toAbsolutePath().toString()));
        var output = run(command, 524288, true, started, deadline);
        try {
            var packets = json.readTree(output.bytes()).path("packets");
            if (!packets.isArray() || packets.isEmpty()) reject();
            long end = 0;
            for (int i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                if (!packet.path("pts").isIntegralNumber()
                        || !packet.path("duration").isIntegralNumber()) reject();
                long start = packet.path("pts").asLong();
                long duration = packet.path("duration").asLong();
                if ((i == 0 && start != -1024 && start != 0)
                        || (i > 0 && start != end)
                        || duration < 1
                        || duration > 1024
                        || (i < packets.size() - 1 && duration != 1024)) reject();
                end = Math.addExact(start, duration);
            }
            if (end < 1) reject();
            if (end > (long) rate * 30)
                throw new RoleplayUploadSpoolFactory.Rejected(
                        RoleplayUploadSpoolFactory.Reason.TOO_LONG);
            return end;
        } catch (RoleplayUploadSpoolFactory.Rejected rejection) {
            throw rejection;
        } catch (RuntimeException invalidPackets) {
            reject();
            return 0;
        } finally {
            Arrays.fill(output.bytes(), (byte) 0);
        }
    }

    private void addInputOptions(List<String> command) {
        command.addAll(
                List.of(
                        "-protocol_whitelist",
                        "file,pipe",
                        "-format_whitelist",
                        FORMATS,
                        "-probesize",
                        "2097152",
                        "-analyzeduration",
                        "1000000",
                        "-max_alloc",
                        "16777216"));
    }

    private Output run(
            List<String> command,
            long maximum,
            boolean capture,
            long started,
            RoleplayTurnDeadline deadline)
            throws IOException {
        Process process;
        try {
            process =
                    new ProcessBuilder(command)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
        } catch (IOException unavailable) {
            throw new RoleplayAudioInspector.Unavailable();
        }
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        Future<Output> reader = null;
        try {
            process.getOutputStream().close();
            reader = readers.submit(() -> read(process.getInputStream(), maximum, capture));
            Output output;
            while (true) {
                checkTime(started, deadline);
                try {
                    output = reader.get(20, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException waiting) {
                }
            }
            while (!process.waitFor(20, TimeUnit.MILLISECONDS)) checkTime(started, deadline);
            checkTime(started, deadline);
            if (process.exitValue() != 0) reject();
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            deadline.cancel();
            deadline.requireActive();
            throw new AssertionError();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new RoleplayAudioInspector.Unavailable();
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.getInputStream().close();
            if (reader != null) reader.cancel(true);
            readers.shutdownNow();
        }
    }

    private Output read(InputStream source, long maximum, boolean capture) throws IOException {
        var bytes = new byte[8192];
        var saved = capture ? new ByteArrayOutputStream() : null;
        long count = 0;
        try {
            int size;
            while ((size = source.read(bytes)) >= 0) {
                count += size;
                if (count > maximum)
                    throw new RoleplayUploadSpoolFactory.Rejected(
                            capture
                                    ? RoleplayUploadSpoolFactory.Reason.INVALID_AUDIO
                                    : RoleplayUploadSpoolFactory.Reason.TOO_LONG);
                if (capture) saved.write(bytes, 0, size);
            }
            return new Output(capture ? saved.toByteArray() : new byte[0], count);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void checkTime(long started, RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        if (System.nanoTime() - started >= timeoutNanos)
            throw new RoleplayAudioInspector.Unavailable();
    }

    private boolean webmDocType(Path file) throws IOException {
        byte[] prefix;
        try (var in = Files.newInputStream(file)) {
            prefix = in.readNBytes(4096);
        }
        if (prefix.length < 5
                || prefix[0] != 0x1a
                || (prefix[1] & 255) != 0x45
                || (prefix[2] & 255) != 0xdf
                || (prefix[3] & 255) != 0xa3) return false;
        var headerSize = ebmlValue(prefix, 4, false);
        long end = 4L + headerSize.width() + headerSize.value();
        if (end > prefix.length) return false;
        int position = 4 + headerSize.width();
        boolean found = false;
        while (position < end) {
            var id = ebmlValue(prefix, position, true);
            position += id.width();
            var size = ebmlValue(prefix, position, false);
            position += size.width();
            long next = position + size.value();
            if (next > end) return false;
            if (id.value() == 0x4282) {
                if (found
                        || size.value() != 4
                        || prefix[position] != 'w'
                        || prefix[position + 1] != 'e'
                        || prefix[position + 2] != 'b'
                        || prefix[position + 3] != 'm') return false;
                found = true;
            }
            position = (int) next;
        }
        return found;
    }

    private EbmlValue ebmlValue(byte[] bytes, int position, boolean id) {
        if (position >= bytes.length) {
            reject();
        }
        int first = bytes[position] & 255;
        int mask = 128, width = 1;
        while (mask > 0 && (first & mask) == 0) {
            mask >>= 1;
            width++;
        }
        if (mask == 0 || width > (id ? 4 : 8) || position + width > bytes.length) reject();
        long value = id ? first : first & (mask - 1);
        for (int i = 1; i < width; i++) value = (value << 8) | (bytes[position + i] & 255);
        return new EbmlValue(value, width);
    }

    private record EbmlValue(long value, int width) {}

    private boolean mp4Brand(Path file) throws IOException {
        try (var in = new DataInputStream(Files.newInputStream(file))) {
            int size = in.readInt();
            return size >= 16
                    && in.readInt() == 0x66747970
                    && Set.of(
                                    0x4d344120,
                                    0x69736f6d,
                                    0x69736f32,
                                    0x6d703431,
                                    0x6d703432,
                                    0x64617368)
                            .contains(in.readInt());
        }
    }

    private static void reject() {
        throw new RoleplayUploadSpoolFactory.Rejected(
                RoleplayUploadSpoolFactory.Reason.INVALID_AUDIO);
    }

    private record Output(byte[] bytes, long count) {}
}
