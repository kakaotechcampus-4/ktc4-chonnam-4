package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

/**
 * STT 결과. text 는 STT 원문이며 입력 처리·안전 처리 전이므로 그대로 LlmRequest 에 넣지 않는다.
 *
 * <p>confidence 는 제공자가 토큰 logprob 을 돌려줄 때만 채운다: exp(평균 logprob), 0~1. 저신뢰 판정(0.40 이하)과 임계값 보정은 RPL
 * 입력 처리의 책임이며 이 값은 근거로만 쓴다. 무음이면 제공자는 빈 text 를 돌려주고 이것은 제공자 실패가 아니다({@link #hasSpeech()}).
 *
 * <p>toString 은 발화 원문을 포함하지 않는다.
 */
public record SpeechTranscription(
        String text,
        Double confidence,
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens) {

    public SpeechTranscription {
        Objects.requireNonNull(text, "text must not be null");
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public boolean hasSpeech() {
        return !text.isBlank();
    }

    @Override
    public String toString() {
        return "SpeechTranscription[hasSpeech="
                + hasSpeech()
                + ", confidence="
                + confidence
                + ", provider="
                + provider
                + ", model="
                + model
                + "]";
    }
}
