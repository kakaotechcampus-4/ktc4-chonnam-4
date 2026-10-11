package com.neuringo.neuringobe.ai.infrastructure.speech;

import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.assertFailure;
import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.assertSuccess;
import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.turnTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechEmotion;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TypecastTextToSpeechProviderTest {

    private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x20};
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StubHttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new StubHttpServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void sendsTypecastRequestAndReturnsAudio() {
        server.respond(200, "audio/mpeg", MP3);

        SpeechSynthesisRequest request =
                new SpeechSynthesisRequest(turnTrace(), 1, "같이 놀자!", null, SpeechEmotion.HAPPY);
        AiCallResult<SynthesizedSpeech> result = provider("ssfm-v30").synthesize(request);

        AiCallResult.Success<SynthesizedSpeech> success = assertSuccess(result);
        assertThat(success.data().audio()).isEqualTo(MP3);
        assertThat(success.data().format()).isEqualTo(AudioFormat.MP3);
        assertThat(success.data().voiceId()).isEqualTo("tc_default");
        assertThat(success.metadata().operation()).isEqualTo(AiOperation.SPEECH_SYNTHESIS);
        assertThat(success.metadata().model()).isEqualTo("ssfm-v30");

        assertThat(server.requests()).hasSize(1);
        StubHttpServer.RecordedRequest sent = server.requests().getFirst();
        assertThat(sent.path()).isEqualTo("/v1/text-to-speech");
        assertThat(sent.header("X-API-KEY")).isEqualTo("test-key");
        assertThat(sent.header("Authorization")).isNull();
        JsonNode body = JSON.readTree(new String(sent.body(), StandardCharsets.UTF_8));
        assertThat(body.path("voice_id").asString()).isEqualTo("tc_default");
        assertThat(body.path("text").asString()).isEqualTo("같이 놀자!");
        assertThat(body.path("model").asString()).isEqualTo("ssfm-v30");
        assertThat(body.path("language").asString()).isEqualTo("kor");
        assertThat(body.path("prompt").path("emotion_type").asString()).isEqualTo("preset");
        assertThat(body.path("prompt").path("emotion_preset").asString()).isEqualTo("happy");
        assertThat(body.path("output").path("audio_format").asString()).isEqualTo("mp3");
    }

    @Test
    void usesRequestVoiceOverDefault() {
        server.respond(200, "audio/mpeg", MP3);

        provider("ssfm-v30")
                .synthesize(new SpeechSynthesisRequest(turnTrace(), 1, "안녕", "tc_mascot", null));

        JsonNode body =
                JSON.readTree(
                        new String(server.requests().getFirst().body(), StandardCharsets.UTF_8));
        assertThat(body.path("voice_id").asString()).isEqualTo("tc_mascot");
        assertThat(body.path("prompt").path("emotion_preset").asString()).isEqualTo("normal");
    }

    @Test
    void omitsEmotionTypeForSsfmV21() {
        server.respond(200, "audio/mpeg", MP3);

        provider("ssfm-v21")
                .synthesize(new SpeechSynthesisRequest(turnTrace(), 1, "안녕", null, null));

        JsonNode body =
                JSON.readTree(
                        new String(server.requests().getFirst().body(), StandardCharsets.UTF_8));
        assertThat(body.path("prompt").has("emotion_type")).isFalse();
    }

    @Test
    void fallsBackToConfiguredFormatWhenContentTypeIsUnknown() {
        server.respond(200, "application/octet-stream", MP3);

        AiCallResult<SynthesizedSpeech> result =
                provider("ssfm-v30")
                        .synthesize(new SpeechSynthesisRequest(turnTrace(), 1, "안녕", null, null));

        assertThat(assertSuccess(result).data().format()).isEqualTo(AudioFormat.MP3);
    }

    @Test
    void mapsEmptyAudioToEmptyOutput() {
        server.respond(200, "audio/mpeg", new byte[0]);

        assertFailure(
                provider("ssfm-v30")
                        .synthesize(new SpeechSynthesisRequest(turnTrace(), 1, "안녕", null, null)),
                AiFailureType.EMPTY_OUTPUT);
    }

    @ParameterizedTest
    @CsvSource({
        "401, AUTHENTICATION_ERROR",
        "402, PROVIDER_REQUEST_REJECTED",
        "404, PROVIDER_REQUEST_REJECTED",
        "422, PROVIDER_REQUEST_REJECTED",
        "429, RATE_LIMITED",
        "500, PROVIDER_UNAVAILABLE"
    })
    void mapsHttpErrorsWithoutRetrying(int status, AiFailureType expected) {
        server.respondJson(status, "{\"message\":\"같이 놀자!\"}");

        AiCallResult.Failure<SynthesizedSpeech> failure =
                assertFailure(
                        provider("ssfm-v30")
                                .synthesize(
                                        new SpeechSynthesisRequest(
                                                turnTrace(), 1, "같이 놀자!", null, null)),
                        expected);

        assertThat(failure.failure().providerErrorCode()).doesNotContain("놀자");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void mapsSlowResponseToTimeout() {
        server.delay(Duration.ofMillis(800));
        server.respond(200, "audio/mpeg", MP3);

        TypecastTextToSpeechProvider provider =
                new TypecastTextToSpeechProvider(
                        server.baseUrl(),
                        "test-key",
                        Duration.ofMillis(200),
                        new OpenAiFailureClassifier(),
                        "typecast",
                        "ssfm-v30",
                        "tc_default",
                        "kor",
                        AudioFormat.MP3);

        assertFailure(
                provider.synthesize(new SpeechSynthesisRequest(turnTrace(), 1, "안녕", null, null)),
                AiFailureType.TIMEOUT);
    }

    @Test
    void rejectsOutputFormatsTypecastDoesNotProduce() {
        assertThatThrownBy(
                        () ->
                                new TypecastTextToSpeechProvider(
                                        server.baseUrl(),
                                        "test-key",
                                        Duration.ofSeconds(1),
                                        new OpenAiFailureClassifier(),
                                        "typecast",
                                        "ssfm-v30",
                                        "tc_default",
                                        "kor",
                                        AudioFormat.WEBM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(SpeechEmotion.class)
    void mapsEveryEmotionToTypecastPreset(SpeechEmotion emotion) {
        assertThat(TypecastTextToSpeechProvider.emotionPreset(emotion))
                .isIn("normal", "happy", "sad", "angry", "whisper", "toneup", "tonedown");
    }

    private TypecastTextToSpeechProvider provider(String model) {
        return new TypecastTextToSpeechProvider(
                server.baseUrl(),
                "test-key",
                Duration.ofSeconds(2),
                new OpenAiFailureClassifier(),
                "typecast",
                model,
                "tc_default",
                "kor",
                AudioFormat.MP3);
    }
}
