package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 로컬 끝까지 돌려 보기 전용 STT(neuringo.ai.stt.provider=scripted). 실제 음성 인식 대신, 올린 WAV 파일 안의 {@code nrgo}
 * 조각에 든 UTF-8 글을 아동이 말한 것으로 돌려준다. 그래서 같은 답 대본으로 몇 번을 돌려도 결과가 같고, 여러 세션이 동시에 돌아도 섞이지 않는다.
 *
 * <p>원인 판단·응답 생성은 실제 LLM 이 하므로 팀 역할극 판단 경로는 그대로 지난다. 운영 설정에는 쓰지 않는다. 조각이 없으면 빈 글(무음)을 돌려줘 서버가
 * NO_SPEECH 로 처리한다. 신뢰도는 저신뢰 기준(0.40)을 넘는 고정값이다.
 */
public final class ScriptedSpeechToTextProvider implements SpeechToTextProvider {
    static final String CHUNK_ID = "nrgo";
    static final double CONFIDENCE = 0.95;

    @Override
    public AiCallResult<SpeechTranscription> transcribe(SpeechTranscriptionRequest request) {
        String text = request.format() == AudioFormat.WAV ? scriptedText(request.audio()) : "";
        return new AiCallResult.Success<>(
                new SpeechTranscription(text, CONFIDENCE, "scripted", "scripted-stt", null, null),
                new AiCallMetadata(
                        request.traceContext().requestId(),
                        AiOperation.SPEECH_TRANSCRIPTION,
                        "scripted",
                        "scripted-stt",
                        null,
                        null,
                        null,
                        0,
                        request.currentAttempt(),
                        null,
                        null,
                        null));
    }

    /** RIFF 조각을 차례로 읽어 nrgo 조각의 글을 찾는다. 모양이 맞지 않으면 빈 글이다. */
    static String scriptedText(byte[] wav) {
        if (wav.length < 12
                || !"RIFF".equals(new String(wav, 0, 4, StandardCharsets.US_ASCII))
                || !"WAVE".equals(new String(wav, 8, 4, StandardCharsets.US_ASCII))) {
            return "";
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int position = 12;
        while (position + 8 <= wav.length) {
            String id = new String(wav, position, 4, StandardCharsets.US_ASCII);
            int size = buffer.getInt(position + 4);
            int start = position + 8;
            if (size < 0 || start + size > wav.length) {
                return "";
            }
            if (CHUNK_ID.equals(id)) {
                return new String(wav, start, size, StandardCharsets.UTF_8).trim();
            }
            // RIFF 조각은 짝수 바이트로 맞춘다.
            position = start + size + (size % 2);
        }
        return "";
    }
}
