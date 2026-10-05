package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

/**
 * 아동 음성 한 번을 텍스트로 바꾸는 요청. 원본 음성은 이 요청과 함께 제공자에게만 전달하고 저장·로그에 남기지 않는다(VS-010, DEC-007).
 *
 * <p>toString 은 음성 내용을 포함하지 않는다.
 */
public record SpeechTranscriptionRequest(
        AiTraceContext traceContext, int currentAttempt, byte[] audio, AudioFormat format) {

    /**
     * 역할극 한 턴 발화의 업로드 상한(2MB). webm/opus(약 128kbps) 약 2분, wav(16kHz 모노) 약 1분이다. OpenAI 한도(25MB)까지
     * 받으면 5~10분 녹음이 들어와 전사만 1분 가까이 걸리고 STT 제한 시간에 걸려 재시도만 반복된다. 녹음 길이 자체는 FE 에서 제한한다.
     */
    public static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;

    public SpeechTranscriptionRequest {
        Objects.requireNonNull(traceContext, "traceContext must not be null");
        Objects.requireNonNull(traceContext.sessionId(), "sessionId is required");
        Objects.requireNonNull(traceContext.turnId(), "turnId is required");
        Objects.requireNonNull(audio, "audio must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (currentAttempt < 1) {
            throw new IllegalArgumentException("currentAttempt must be at least 1");
        }
        if (audio.length == 0) {
            throw new IllegalArgumentException("audio must not be empty");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("audio exceeds " + MAX_AUDIO_BYTES + " bytes");
        }
        audio = audio.clone();
    }

    @Override
    public byte[] audio() {
        return audio.clone();
    }

    @Override
    public String toString() {
        return "SpeechTranscriptionRequest[requestId="
                + traceContext.requestId()
                + ", attempt="
                + currentAttempt
                + ", format="
                + format
                + ", bytes="
                + audio.length
                + "]";
    }
}
