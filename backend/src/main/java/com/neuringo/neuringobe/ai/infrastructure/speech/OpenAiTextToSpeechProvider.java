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
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 호환 음성 합성 API(POST /v1/audio/speech) 어댑터. 기본 모델은 gpt-4o-mini-tts 다. STT 와 같은 OpenAI 키로 쓸 수
 * 있다.
 *
 * <p>gpt-4o 계열 모델에는 아이에게 말하듯 천천히 또렷하게 읽으라는 지시(instructions)를 함께 보낸다. tts-1 계열은 이 필드를 받지 않아 빼고 보낸다.
 * 문장과 제공자 오류 본문은 로그와 실패 결과에 남기지 않는다.
 */
public final class OpenAiTextToSpeechProvider implements TextToSpeechProvider {

    static final String SPEECH_PATH = "/v1/audio/speech";

    private static final String BASE_INSTRUCTIONS = "한국어로 어린이에게 말하듯 천천히, 또렷하고 다정하게 읽어 주세요.";

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final OpenAiFailureClassifier failureClassifier;
    private final String providerName;
    private final String model;
    private final String defaultVoice;
    private final AudioFormat audioFormat;

    public OpenAiTextToSpeechProvider(
            String baseUrl,
            String apiKey,
            Duration requestTimeout,
            OpenAiFailureClassifier failureClassifier,
            String providerName,
            String model,
            String defaultVoice,
            AudioFormat audioFormat) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        if (audioFormat != AudioFormat.MP3 && audioFormat != AudioFormat.WAV) {
            throw new IllegalArgumentException("openai tts is configured for mp3 or wav output");
        }
        this.httpClient = SpeechHttp.httpClient(requestTimeout);
        this.restClient =
                SpeechHttp.restClient(baseUrl, requestTimeout, httpClient)
                        .mutate()
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build();
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.providerName = Objects.requireNonNull(providerName);
        this.model = Objects.requireNonNull(model);
        this.defaultVoice = defaultVoice;
        this.audioFormat = audioFormat;
    }

    @Override
    public AiCallResult<SynthesizedSpeech> synthesize(SpeechSynthesisRequest request) {
        long startedAt = System.nanoTime();
        String voice = request.voiceId() != null ? request.voiceId() : defaultVoice;
        if (voice == null || voice.isBlank()) {
            throw new IllegalStateException("TTS voice is not configured");
        }

        ResponseEntity<byte[]> response;
        try {
            response =
                    SpeechHttp.forCall(restClient, httpClient, requestTimeout, request.callBudget())
                            .post()
                            .uri(SPEECH_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.ALL)
                            .body(requestBody(request, voice))
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
        return new AiCallResult.Success<>(
                new SynthesizedSpeech(audio, audioFormat, providerName, model, voice),
                metadata(request, startedAt));
    }

    private Map<String, Object> requestBody(SpeechSynthesisRequest request, String voice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", request.text());
        body.put("voice", voice);
        body.put("response_format", audioFormat.fileExtension());
        if (supportsInstructions()) {
            body.put("instructions", instructions(request.emotion()));
        }
        return body;
    }

    private boolean supportsInstructions() {
        return !model.startsWith("tts-1");
    }

    static String instructions(SpeechEmotion emotion) {
        String tone =
                switch (emotion) {
                    case NORMAL -> "";
                    case HAPPY -> " 밝고 신나는 말투로 말해 주세요.";
                    case SAD -> " 조금 속상한 말투로 말해 주세요.";
                    case ANGRY -> " 조금 화난 말투로, 그래도 무섭지 않게 말해 주세요.";
                    case WHISPER -> " 속삭이듯 작게 말해 주세요.";
                    case TONE_UP -> " 조금 높은 목소리로 말해 주세요.";
                    case TONE_DOWN -> " 조금 낮은 목소리로 말해 주세요.";
                };
        return BASE_INSTRUCTIONS + tone;
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
