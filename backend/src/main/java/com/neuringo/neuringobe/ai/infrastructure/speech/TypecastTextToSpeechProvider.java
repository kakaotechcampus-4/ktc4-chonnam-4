package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechEmotion;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.FailureMapping;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * 타입캐스트 TTS API(POST /v1/text-to-speech) 어댑터. 응답 본문은 음성 바이너리다.
 *
 * <p>HTTP 오류는 공통 분류를 따른다: 401·403 인증, 402(크레딧 부족)·422 요청 거부, 429 속도 제한, 5xx 제공자 장애. 문장과 제공자 오류 본문은
 * 로그와 실패 결과에 남기지 않는다.
 */
public final class TypecastTextToSpeechProvider implements TextToSpeechProvider {

    static final String TTS_PATH = "/v1/text-to-speech";

    private final RestClient restClient;
    private final OpenAiFailureClassifier failureClassifier;
    private final String providerName;
    private final String model;
    private final String defaultVoiceId;
    private final String language;
    private final AudioFormat audioFormat;

    public TypecastTextToSpeechProvider(
            String baseUrl,
            String apiKey,
            Duration requestTimeout,
            OpenAiFailureClassifier failureClassifier,
            String providerName,
            String model,
            String defaultVoiceId,
            String language,
            AudioFormat audioFormat) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        if (audioFormat != AudioFormat.MP3 && audioFormat != AudioFormat.WAV) {
            throw new IllegalArgumentException("typecast supports only mp3 or wav output");
        }
        this.restClient =
                SpeechHttp.restClient(baseUrl, requestTimeout)
                        .mutate()
                        .defaultHeader("X-API-KEY", apiKey)
                        .build();
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.providerName = Objects.requireNonNull(providerName);
        this.model = Objects.requireNonNull(model);
        this.defaultVoiceId = defaultVoiceId;
        this.language = language;
        this.audioFormat = audioFormat;
    }

    @Override
    public AiCallResult<SynthesizedSpeech> synthesize(SpeechSynthesisRequest request) {
        long startedAt = System.nanoTime();
        String voiceId = request.voiceId() != null ? request.voiceId() : defaultVoiceId;
        if (voiceId == null || voiceId.isBlank()) {
            throw new IllegalStateException("TTS voiceId is not configured");
        }

        ResponseEntity<byte[]> response;
        try {
            response =
                    restClient
                            .post()
                            .uri(TTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.ALL)
                            .body(requestBody(request, voiceId))
                            .retrieve()
                            .toEntity(byte[].class);
        } catch (RuntimeException exception) {
            FailureMapping mapping =
                    failureClassifier.classify(exception).orElseThrow(() -> exception);
            return new AiCallResult.Failure<>(mapping.failure(), metadata(request, startedAt));
        }

        byte[] audio = response.getBody();
        if (audio == null || audio.length == 0) {
            return new AiCallResult.Failure<>(
                    new AiFailure(AiFailureType.EMPTY_OUTPUT, null), metadata(request, startedAt));
        }
        AudioFormat format = responseFormat(response);
        return new AiCallResult.Success<>(
                new SynthesizedSpeech(audio, format, providerName, model, voiceId),
                metadata(request, startedAt));
    }

    private Map<String, Object> requestBody(SpeechSynthesisRequest request, String voiceId) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        // ssfm-v30 은 emotion_type 으로 프리셋 방식임을 밝혀야 한다. ssfm-v21 은 이 필드가 없다.
        if (!model.startsWith("ssfm-v21")) {
            prompt.put("emotion_type", "preset");
        }
        prompt.put("emotion_preset", emotionPreset(request.emotion()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("voice_id", voiceId);
        body.put("text", request.text());
        body.put("model", model);
        if (language != null && !language.isBlank()) {
            body.put("language", language);
        }
        body.put("prompt", prompt);
        body.put("output", Map.of("audio_format", audioFormat.fileExtension()));
        return body;
    }

    static String emotionPreset(SpeechEmotion emotion) {
        return switch (emotion) {
            case NORMAL -> "normal";
            case HAPPY -> "happy";
            case SAD -> "sad";
            case ANGRY -> "angry";
            case WHISPER -> "whisper";
            case TONE_UP -> "toneup";
            case TONE_DOWN -> "tonedown";
        };
    }

    private AudioFormat responseFormat(ResponseEntity<byte[]> response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null) {
            return audioFormat;
        }
        try {
            return AudioFormat.fromMediaType(contentType.toString());
        } catch (IllegalArgumentException exception) {
            return audioFormat;
        }
    }

    private AiCallMetadata metadata(SpeechSynthesisRequest request, long startedAt) {
        return SpeechHttp.metadata(
                request.traceContext().requestId(),
                AiOperation.SPEECH_SYNTHESIS,
                providerName,
                model,
                startedAt,
                request.currentAttempt(),
                null,
                null);
    }
}
