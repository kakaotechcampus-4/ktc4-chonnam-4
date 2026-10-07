package com.neuringo.neuringobe.ai.application.model;

import java.util.Locale;
import java.util.Objects;

/** STT 입력과 TTS 출력에 쓰는 음성 형식. 브라우저 MediaRecorder 의 기본값(Chrome 은 webm/opus)을 그대로 받는다. */
public enum AudioFormat {
    WEBM("audio/webm", "webm"),
    OGG("audio/ogg", "ogg"),
    WAV("audio/wav", "wav"),
    MP3("audio/mpeg", "mp3"),
    MP4("audio/mp4", "m4a");

    private final String mediaType;
    private final String fileExtension;

    AudioFormat(String mediaType, String fileExtension) {
        this.mediaType = mediaType;
        this.fileExtension = fileExtension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String fileExtension() {
        return fileExtension;
    }

    /** "audio/webm;codecs=opus" 처럼 파라미터가 붙은 값도 받는다. 모르는 형식이면 IllegalArgumentException. */
    public static AudioFormat fromMediaType(String mediaType) {
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        String base = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (base) {
            case "audio/webm", "video/webm" -> WEBM;
            case "audio/ogg" -> OGG;
            case "audio/wav", "audio/x-wav", "audio/wave" -> WAV;
            case "audio/mpeg", "audio/mp3" -> MP3;
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> MP4;
            default -> throw new IllegalArgumentException("unsupported audio media type");
        };
    }
}
