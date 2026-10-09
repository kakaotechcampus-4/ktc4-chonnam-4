package com.neuringo.neuringobe.ai.application.model;

import java.util.Objects;

/**
 * 전달이 확정된 문장을 음성으로 바꾸는 요청. 검증을 통과한 후보(EvaluationResult.canDeliver)나 고정 안내 문구만 넣는다.
 *
 * <p>voiceId 가 비어 있으면 설정의 기본 목소리를 쓴다. emotion 이 비어 있으면 NORMAL 이다. toString 은 문장을 포함하지 않는다.
 */
public record SpeechSynthesisRequest(
        AiTraceContext traceContext,
        int currentAttempt,
        String text,
        String voiceId,
        SpeechEmotion emotion,
        AiCallBudget callBudget) {

    public SpeechSynthesisRequest(
            AiTraceContext traceContext,
            int currentAttempt,
            String text,
            String voiceId,
            SpeechEmotion emotion) {
        this(traceContext, currentAttempt, text, voiceId, emotion, null);
    }

    /** 타입캐스트 한 번 요청의 문장 길이 한도. */
    public static final int MAX_TEXT_LENGTH = 2000;

    public SpeechSynthesisRequest {
        Objects.requireNonNull(traceContext, "traceContext must not be null");
        if (currentAttempt < 1) {
            throw new IllegalArgumentException("currentAttempt must be at least 1");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        if (voiceId != null && voiceId.isBlank()) {
            voiceId = null;
        }
        if (emotion == null) {
            emotion = SpeechEmotion.NORMAL;
        }
    }

    @Override
    public String toString() {
        return "SpeechSynthesisRequest[requestId="
                + traceContext.requestId()
                + ", attempt="
                + currentAttempt
                + ", length="
                + text.length()
                + ", voiceId="
                + voiceId
                + ", emotion="
                + emotion
                + "]";
    }
}
