package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.FailureMapping;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 호환 음성 전사 API(POST /v1/audio/transcriptions) 어댑터. 기본 모델은 gpt-4o-mini-transcribe 이다.
 *
 * <p>logprob 을 요청해 신뢰도를 계산한다. whisper-1 처럼 logprob 을 지원하지 않는 모델은 includeLogprobs=false 로 쓰고, 이때
 * confidence 는 비어 있다. 음성·전사 원문·제공자 오류 본문은 로그와 실패 결과에 남기지 않는다.
 */
public final class OpenAiSpeechToTextProvider implements SpeechToTextProvider {

    static final String TRANSCRIPTION_PATH = "/v1/audio/transcriptions";

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final OpenAiFailureClassifier failureClassifier;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String providerName;
    private final String model;
    private final String language;
    private final boolean includeLogprobs;

    public OpenAiSpeechToTextProvider(
            String baseUrl,
            String apiKey,
            Duration requestTimeout,
            OpenAiFailureClassifier failureClassifier,
            String providerName,
            String model,
            String language,
            boolean includeLogprobs) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.httpClient = SpeechHttp.httpClient(requestTimeout);
        this.restClient =
                SpeechHttp.restClient(baseUrl, requestTimeout, httpClient)
                        .mutate()
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build();
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.providerName = Objects.requireNonNull(providerName);
        this.model = Objects.requireNonNull(model);
        this.language = language;
        this.includeLogprobs = includeLogprobs;
    }

    @Override
    public AiCallResult<SpeechTranscription> transcribe(SpeechTranscriptionRequest request) {
        long startedAt = System.nanoTime();

        String body;
        try {
            body =
                    SpeechHttp.forCall(restClient, httpClient, requestTimeout, request.callBudget())
                            .post()
                            .uri(TRANSCRIPTION_PATH)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(multipartBody(request))
                            .retrieve()
                            .body(String.class);
        } catch (RuntimeException exception) {
            FailureMapping mapping =
                    failureClassifier.classify(exception).orElseThrow(() -> exception);
            return new AiCallResult.Failure<>(
                    mapping.failure(), metadata(request, startedAt, null, null));
        }

        return convertResponse(request, startedAt, body);
    }

    private MultiValueMap<String, HttpEntity<?>> multipartBody(SpeechTranscriptionRequest request) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(request.audio()))
                .filename("speech." + request.format().fileExtension())
                .contentType(MediaType.parseMediaType(request.format().mediaType()));
        builder.part("model", model);
        builder.part("response_format", "json");
        if (language != null && !language.isBlank()) {
            builder.part("language", language);
        }
        if (includeLogprobs) {
            builder.part("include[]", "logprobs");
        }
        return builder.build();
    }

    private AiCallResult<SpeechTranscription> convertResponse(
            SpeechTranscriptionRequest request, long startedAt, String body) {
        JsonNode root;
        try {
            root = body == null ? null : jsonMapper.readTree(body);
        } catch (RuntimeException exception) {
            root = null;
        }
        if (root == null || !root.path("text").isString()) {
            return new AiCallResult.Failure<>(
                    new AiFailure(AiFailureType.PROVIDER_RESPONSE_ERROR, null),
                    metadata(request, startedAt, null, null));
        }

        JsonNode usage = root.path("usage");
        Integer inputTokens = intOrNull(usage.path("input_tokens"));
        Integer outputTokens = intOrNull(usage.path("output_tokens"));
        SpeechTranscription transcription =
                new SpeechTranscription(
                        root.path("text").asString().strip(),
                        confidence(root.path("logprobs")),
                        providerName,
                        model,
                        inputTokens,
                        outputTokens);
        return new AiCallResult.Success<>(
                transcription, metadata(request, startedAt, inputTokens, outputTokens));
    }

    /** 토큰 logprob 평균의 지수(기하 평균 확률). logprob 이 없으면 null. */
    static Double confidence(JsonNode logprobs) {
        if (!logprobs.isArray() || logprobs.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        int count = 0;
        for (JsonNode token : logprobs) {
            JsonNode logprob = token.path("logprob");
            if (logprob.isNumber()) {
                sum += Math.min(0.0, logprob.asDouble());
                count++;
            }
        }
        return count == 0 ? null : Math.exp(sum / count);
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isIntegralNumber() ? node.asInt() : null;
    }

    private AiCallMetadata metadata(
            SpeechTranscriptionRequest request,
            long startedAt,
            Integer inputTokens,
            Integer outputTokens) {
        return SpeechHttp.metadata(
                request.traceContext().requestId(),
                AiOperation.SPEECH_TRANSCRIPTION,
                providerName,
                model,
                startedAt,
                request.currentAttempt(),
                inputTokens,
                outputTokens);
    }
}
