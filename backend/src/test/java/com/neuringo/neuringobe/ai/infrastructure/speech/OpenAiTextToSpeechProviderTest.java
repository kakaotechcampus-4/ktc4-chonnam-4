package com.neuringo.neuringobe.ai.infrastructure.speech;

import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.assertFailure;
import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.assertSuccess;
import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.turnTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechEmotion;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
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

class OpenAiTextToSpeechProviderTest {

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
    void sendsSpeechRequestAndReturnsAudio() {
        server.respond(200, "audio/mpeg", MP3);

        var result =
                provider("gpt-4o-mini-tts")
                        .synthesize(
                                new SpeechSynthesisRequest(
                                        turnTrace(), 1, "같이 놀자!", null, SpeechEmotion.HAPPY));

        var success = assertSuccess(result);
        assertThat(success.data().audio()).isEqualTo(MP3);
        assertThat(success.data().format()).isEqualTo(AudioFormat.MP3);
        assertThat(success.data().voiceId()).isEqualTo("nova");
        assertThat(success.metadata().operation()).isEqualTo(AiOperation.SPEECH_SYNTHESIS);
        assertThat(success.metadata().model()).isEqualTo("gpt-4o-mini-tts");

        var sent = server.requests().getFirst();
        assertThat(sent.path()).isEqualTo("/v1/audio/speech");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer test-key");
        JsonNode body = JSON.readTree(new String(sent.body(), StandardCharsets.UTF_8));
        assertThat(body.path("model").asString()).isEqualTo("gpt-4o-mini-tts");
        assertThat(body.path("input").asString()).isEqualTo("같이 놀자!");
        assertThat(body.path("voice").asString()).isEqualTo("nova");
        assertThat(body.path("response_format").asString()).isEqualTo("mp3");
        assertThat(body.path("instructions").asString()).contains("어린이").contains("밝고");
    }

    @Test
    void usesRequestVoiceAndOmitsInstructionsForTts1() {
        server.respond(200, "audio/mpeg", MP3);

        provider("tts-1")
                .synthesize(new SpeechSynthesisRequest(turnTrace(), 1, "안녕", "alloy", null));

        JsonNode body =
                JSON.readTree(
                        new String(server.requests().getFirst().body(), StandardCharsets.UTF_8));
        assertThat(body.path("voice").asString()).isEqualTo("alloy");
        assertThat(body.has("instructions")).isFalse();
    }

    @Test
    void mapsEmptyAudioToEmptyOutput() {
        server.respond(200, "audio/mpeg", new byte[0]);

        assertFailure(
                provider("gpt-4o-mini-tts")
                        .synthesize(new SpeechSynthesisRequest(turnTrace(), 1, "안녕", null, null)),
                AiFailureType.EMPTY_OUTPUT);
    }

    @ParameterizedTest
    @CsvSource({"401, AUTHENTICATION_ERROR", "429, RATE_LIMITED", "500, PROVIDER_UNAVAILABLE"})
    void mapsHttpErrorsWithoutLeakingText(int status, AiFailureType expected) {
        server.respondJson(status, "{\"error\":{\"message\":\"같이 놀자!\"}}");

        var failure =
                assertFailure(
                        provider("gpt-4o-mini-tts")
                                .synthesize(
                                        new SpeechSynthesisRequest(
                                                turnTrace(), 1, "같이 놀자!", null, null)),
                        expected);

        assertThat(String.valueOf(failure.failure().providerErrorCode())).doesNotContain("놀자");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void rejectsUnsupportedOutputFormat() {
        assertThatThrownBy(
                        () ->
                                new OpenAiTextToSpeechProvider(
                                        server.baseUrl(),
                                        "test-key",
                                        Duration.ofSeconds(1),
                                        new OpenAiFailureClassifier(),
                                        "openai",
                                        "gpt-4o-mini-tts",
                                        "nova",
                                        AudioFormat.WEBM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(SpeechEmotion.class)
    void everyEmotionHasKoreanInstructions(SpeechEmotion emotion) {
        assertThat(OpenAiTextToSpeechProvider.instructions(emotion)).startsWith("한국어로");
    }

    private OpenAiTextToSpeechProvider provider(String model) {
        return new OpenAiTextToSpeechProvider(
                server.baseUrl(),
                "test-key",
                Duration.ofSeconds(2),
                new OpenAiFailureClassifier(),
                "openai",
                model,
                "nova",
                AudioFormat.MP3);
    }
}
