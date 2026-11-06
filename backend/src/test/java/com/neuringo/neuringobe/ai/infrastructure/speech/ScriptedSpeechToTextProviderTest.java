package com.neuringo.neuringobe.ai.infrastructure.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScriptedSpeechToTextProviderTest {

    @Test
    void readsTheScriptChunkAfterTheAudioChunks() {
        byte[] wav = wav("친구가 넘어져서 아파 보여");

        assertThat(ScriptedSpeechToTextProvider.scriptedText(wav)).isEqualTo("친구가 넘어져서 아파 보여");
    }

    @Test
    void withoutAScriptChunkOrWavHeaderItIsSilence() {
        assertThat(ScriptedSpeechToTextProvider.scriptedText(wav(null))).isEmpty();
        assertThat(ScriptedSpeechToTextProvider.scriptedText("not a wav".getBytes())).isEmpty();
    }

    /** scripts/local/roleplay-run.mjs 와 같은 모양: RIFF·WAVE, fmt, data(무음 16바이트), 그리고 nrgo 조각. */
    private static byte[] wav(String script) {
        var body = new ByteArrayOutputStream();
        chunk(body, "fmt ", fmt());
        chunk(body, "data", new byte[16]);
        if (script != null) chunk(body, "nrgo", script.getBytes(StandardCharsets.UTF_8));
        byte[] chunks = body.toByteArray();
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le(4 + chunks.length));
        out.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(chunks);
        return out.toByteArray();
    }

    private static byte[] fmt() {
        return ByteBuffer.allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(16000)
                .putInt(32000)
                .putShort((short) 2)
                .putShort((short) 16)
                .array();
    }

    private static void chunk(ByteArrayOutputStream out, String id, byte[] data) {
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le(data.length));
        out.writeBytes(data);
        if (data.length % 2 == 1) out.write(0);
    }

    private static byte[] le(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
