package com.neuringo.neuringobe.ai.application.model;

/** TTS 감정 표현. 제공자별 값으로의 변환은 infrastructure 어댑터가 맡는다. */
public enum SpeechEmotion {
    NORMAL,
    HAPPY,
    SAD,
    ANGRY,
    WHISPER,
    TONE_UP,
    TONE_DOWN
}
