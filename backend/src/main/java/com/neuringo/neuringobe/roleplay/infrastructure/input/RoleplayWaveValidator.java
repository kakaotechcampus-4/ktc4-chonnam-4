package com.neuringo.neuringobe.roleplay.infrastructure.input;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** PCM·실수형 WAV의 프레임 수로 길이를 검사하며 선언 MIME·바이트율만으로 시간을 추정하지 않는다. */
final class RoleplayWaveValidator {
    private static final int RIFF = 0x46464952,
            WAVE = 0x45564157,
            FMT = 0x20746d66,
            DATA = 0x61746164;
    private static final int MAXIMUM_SECONDS = 30;

    private RoleplayWaveValidator() {}

    static void requireValid(Path file, RoleplayTurnDeadline deadline) throws IOException {
        try (var channel =
                FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 12) reject();
            var header = read(channel, 0, 12, deadline);
            if (header.getInt() != RIFF
                    || unsigned(header.getInt()) + 8 != size
                    || header.getInt() != WAVE) reject();
            Format format = null;
            Long dataSize = null;
            long position = 12;
            while (position < size) {
                deadline.requireActive();
                if (size - position < 8) reject();
                var chunk = read(channel, position, 8, deadline);
                int type = chunk.getInt();
                long length = unsigned(chunk.getInt());
                long payload = position + 8;
                long end = payload + length + (length & 1);
                if (end > size) reject();
                if (type == FMT) {
                    if (format != null || length < 16) reject();
                    var values = read(channel, payload, 16, deadline);
                    int encoding = Short.toUnsignedInt(values.getShort());
                    int channels = Short.toUnsignedInt(values.getShort());
                    long rate = unsigned(values.getInt());
                    long byteRate = unsigned(values.getInt());
                    int alignment = Short.toUnsignedInt(values.getShort());
                    int bits = Short.toUnsignedInt(values.getShort());
                    boolean supported =
                            (encoding == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32))
                                    || (encoding == 3 && (bits == 32 || bits == 64));
                    if (!supported)
                        throw new RoleplayUploadSpoolFactory.Rejected(
                                RoleplayUploadSpoolFactory.Reason.UNSUPPORTED_FORMAT);
                    if (channels == 0
                            || rate == 0
                            || alignment != channels * (bits / 8)
                            || byteRate != rate * alignment) reject();
                    format = new Format(rate, alignment);
                } else if (type == DATA) {
                    if (dataSize != null || format == null) reject();
                    dataSize = length;
                }
                position = end;
            }
            if (format == null
                    || dataSize == null
                    || dataSize == 0
                    || dataSize % format.alignment() != 0) reject();
            long frames = dataSize / format.alignment();
            if (frames > format.sampleRate() * MAXIMUM_SECONDS)
                throw new RoleplayUploadSpoolFactory.Rejected(
                        RoleplayUploadSpoolFactory.Reason.TOO_LONG);
            deadline.requireActive();
        }
    }

    private static ByteBuffer read(
            FileChannel channel, long position, int size, RoleplayTurnDeadline deadline)
            throws IOException {
        var bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (bytes.hasRemaining()) {
            deadline.requireActive();
            int count = channel.read(bytes, position + bytes.position());
            if (count < 0) reject();
        }
        return bytes.flip();
    }

    private static long unsigned(int value) {
        return Integer.toUnsignedLong(value);
    }

    private static void reject() {
        throw new RoleplayUploadSpoolFactory.Rejected(
                RoleplayUploadSpoolFactory.Reason.INVALID_AUDIO);
    }

    private record Format(long sampleRate, int alignment) {}
}
