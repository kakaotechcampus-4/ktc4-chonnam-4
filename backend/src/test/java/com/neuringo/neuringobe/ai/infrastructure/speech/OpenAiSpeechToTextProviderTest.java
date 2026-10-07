package com.neuringo.neuringobe.ai.infrastructure.speech;

import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.assertFailure;
import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.assertSuccess;
import static com.neuringo.neuringobe.ai.infrastructure.speech.SpeechTestFixtures.turnTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenAiSpeechToTextProviderTest {

    private static final byte[] AUDIO = {1, 2, 3, 4};

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
    void sendsMultipartRequestAndConvertsTranscription() {
        server.respondJson(
                200,
                """
                {"text":" 친구한테 괜찮냐고 물어볼래요 ",
                 "logprobs":[{"token":"친","logprob":-0.1},{"token":"구","logprob":-0.3}],
                 "usage":{"type":"tokens","input_tokens":30,"output_tokens":9,"total_tokens":39}}
                """);

        SpeechTranscriptionRequest request = request(AudioFormat.WEBM);
        AiCallResult<SpeechTranscription> result = provider(true).transcribe(request);

        AiCallResult.Success<SpeechTranscription> success = assertSuccess(result);
        assertThat(success.data().text()).isEqualTo("친구한테 괜찮냐고 물어볼래요");
        assertThat(success.data().hasSpeech()).isTrue();
        assertThat(success.data().confidence()).isCloseTo(Math.exp(-0.2), within(1e-9));
        assertThat(success.data().model()).isEqualTo("gpt-4o-mini-transcribe");
        assertThat(success.metadata().operation()).isEqualTo(AiOperation.SPEECH_TRANSCRIPTION);
        assertThat(success.metadata().requestId()).isEqualTo(request.traceContext().requestId());
        assertThat(success.metadata().inputTokens()).isEqualTo(30);
        assertThat(success.metadata().outputTokens()).isEqualTo(9);
        assertThat(success.metadata().promptVersion()).isNull();

        assertThat(server.requests()).hasSize(1);
        StubHttpServer.RecordedRequest sent = server.requests().getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1/audio/transcriptions");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer test-key");
        assertThat(sent.header("Content-Type")).startsWith("multipart/form-data");
        String body = sent.bodyAsString();
        assertThat(body).contains("filename=\"speech.webm\"");
        assertThat(body).contains("Content-Type: audio/webm");
        assertThat(body).contains("name=\"model\"").contains("gpt-4o-mini-transcribe");
        assertThat(body).contains("name=\"language\"");
        assertThat(body).contains("name=\"response_format\"").contains("json");
        assertThat(body).contains("name=\"include[]\"").contains("logprobs");
    }

    @Test
    void omitsLogprobsWhenDisabledAndLeavesConfidenceEmpty() {
        server.respondJson(200, "{\"text\":\"네\"}");

        AiCallResult<SpeechTranscription> result =
                provider(false).transcribe(request(AudioFormat.WAV));

        AiCallResult.Success<SpeechTranscription> success = assertSuccess(result);
        assertThat(success.data().confidence()).isNull();
        assertThat(success.data().inputTokens()).isNull();
        assertThat(server.requests().getFirst().bodyAsString()).doesNotContain("include[]");
    }

    @Test
    void treatsEmptyTextAsSilenceNotFailure() {
        server.respondJson(200, "{\"text\":\"\"}");

        AiCallResult<SpeechTranscription> result =
                provider(true).transcribe(request(AudioFormat.WEBM));

        assertThat(assertSuccess(result).data().hasSpeech()).isFalse();
    }

    @Test
    void mapsMissingTextToProviderResponseError() {
        server.respondJson(200, "{\"unexpected\":true}");

        assertFailure(
                provider(true).transcribe(request(AudioFormat.WEBM)),
                AiFailureType.PROVIDER_RESPONSE_ERROR);
    }

    @Test
    void mapsNonJsonBodyToProviderResponseError() {
        server.respond(200, "text/plain", "not json".getBytes());

        assertFailure(
                provider(true).transcribe(request(AudioFormat.WEBM)),
                AiFailureType.PROVIDER_RESPONSE_ERROR);
    }

    @ParameterizedTest
    @CsvSource({
        "400, PROVIDER_REQUEST_REJECTED",
        "401, AUTHENTICATION_ERROR",
        "403, AUTHENTICATION_ERROR",
        "413, PROVIDER_REQUEST_REJECTED",
        "429, RATE_LIMITED",
        "500, PROVIDER_UNAVAILABLE",
        "503, PROVIDER_UNAVAILABLE"
    })
    void mapsHttpErrorsWithoutRetrying(int status, AiFailureType expected) {
        server.respondJson(status, "{\"error\":{\"message\":\"child said something\"}}");

        AiCallResult.Failure<SpeechTranscription> failure =
                assertFailure(provider(true).transcribe(request(AudioFormat.WEBM)), expected);

        assertThat(failure.failure().retryable()).isEqualTo(expected.retryable());
        assertThat(failure.failure().providerErrorCode()).doesNotContain("child said");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void mapsSlowResponseToTimeout() {
        server.delay(Duration.ofMillis(800));
        server.respondJson(200, "{\"text\":\"늦은 응답\"}");

        assertFailure(
                provider(true, Duration.ofMillis(200)).transcribe(request(AudioFormat.WEBM)),
                AiFailureType.TIMEOUT);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void mapsConnectionFailureToNetworkError() {
        String unreachable = server.baseUrl();
        server.close();

        OpenAiSpeechToTextProvider provider =
                new OpenAiSpeechToTextProvider(
                        unreachable,
                        "test-key",
                        Duration.ofSeconds(1),
                        new OpenAiFailureClassifier(),
                        "openai",
                        "gpt-4o-mini-transcribe",
                        "ko",
                        true);

        assertFailure(provider.transcribe(request(AudioFormat.WEBM)), AiFailureType.NETWORK_ERROR);
    }

    @Test
    void confidenceIgnoresTokensWithoutLogprobAndClampsPositiveValues() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        assertThat(OpenAiSpeechToTextProvider.confidence(mapper.readTree("[]"))).isNull();
        assertThat(OpenAiSpeechToTextProvider.confidence(mapper.readTree("[{\"token\":\"a\"}]")))
                .isNull();
        assertThat(
                        OpenAiSpeechToTextProvider.confidence(
                                mapper.readTree("[{\"logprob\":0.5},{\"logprob\":0.0}]")))
                .isEqualTo(1.0);
    }

    private OpenAiSpeechToTextProvider provider(boolean includeLogprobs) {
        return provider(includeLogprobs, Duration.ofSeconds(2));
    }

    private OpenAiSpeechToTextProvider provider(boolean includeLogprobs, Duration timeout) {
        return new OpenAiSpeechToTextProvider(
                server.baseUrl(),
                "test-key",
                timeout,
                new OpenAiFailureClassifier(),
                "openai",
                "gpt-4o-mini-transcribe",
                "ko",
                includeLogprobs);
    }

    private static SpeechTranscriptionRequest request(AudioFormat format) {
        return new SpeechTranscriptionRequest(turnTrace(), 1, AUDIO, format);
    }
}
