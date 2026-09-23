package com.neuringo.neuringobe.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.Provider.OPENAI;
import static org.mockserver.model.Usage.usage;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.config.AiProviderConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.Delay;
import org.mockserver.testcontainers.MockServerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = MockServerLlmIntegrationTest.TestApplication.class)
class MockServerLlmIntegrationTest {

    private static final MockServerContainer MOCK_SERVER = new MockServerContainer();

    static {
        MOCK_SERVER.start();
    }

    @Autowired private LlmProvider llmProvider;

    private MockServerClient mockServerClient;

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.model.chat", () -> "openai");
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.openai.base-url", () -> MOCK_SERVER.getEndpoint() + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.ai.openai.timeout", () -> "500ms");
        registry.add("spring.ai.openai.max-retries", () -> "0");
        registry.add("spring.ai.openai.chat.model", () -> "test-model");
        registry.add("neuringo.ai.provider-name", () -> "mockserver");
        registry.add("neuringo.ai.request-timeout", () -> "500ms");
        registry.add("neuringo.ai.max-retries", () -> "0");
    }

    @AfterAll
    static void stopMockServer() {
        MOCK_SERVER.close();
    }

    @BeforeEach
    void resetMockServer() {
        mockServerClient = MOCK_SERVER.getClient();
        mockServerClient.reset();
    }

    @Test
    void convertsOpenAiCompatibleResponseToCompletion() {
        llmMock("/v1/chat/completions")
                .withProvider(OPENAI)
                .withModel("test-model")
                .respondingWith(
                        completion()
                                .withText("{\"learning_state\":\"PARTIAL_UNDERSTANDING\"}")
                                .withStopReason("stop")
                                .withUsage(usage().withInputTokens(12).withOutputTokens(8)))
                .applyTo(mockServerClient);

        AiCallResult<LlmCompletion> result = llmProvider.complete(requestPayload());

        assertThat(result).isInstanceOf(AiCallResult.Success.class);
        AiCallResult.Success<LlmCompletion> success = (AiCallResult.Success<LlmCompletion>) result;
        assertThat(success.data().content())
                .isEqualTo("{\"learning_state\":\"PARTIAL_UNDERSTANDING\"}");
        assertThat(success.data().provider()).isEqualTo("mockserver");
        assertThat(success.data().model()).isEqualTo("test-model");
        assertThat(success.data().inputTokens()).isEqualTo(12);
        assertThat(success.data().outputTokens()).isEqualTo(8);
        verifyCalledOnce();
    }

    @Test
    void mapsDelayedResponseToTimeoutWithoutRetrying() {
        mockServerClient
                .when(request().withMethod("POST").withPath("/v1/chat/completions"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withDelay(Delay.seconds(1))
                                .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                                .withBody("{}"));

        AiCallResult<LlmCompletion> result = llmProvider.complete(requestPayload());

        assertFailureType(result, AiFailureType.TIMEOUT);
        verifyCalledOnce();
    }

    @Test
    void mapsRateLimitResponseWithoutRetrying() {
        respondWithError(429);

        AiCallResult<LlmCompletion> result = llmProvider.complete(requestPayload());

        assertFailureType(result, AiFailureType.RATE_LIMITED);
        verifyCalledOnce();
    }

    @Test
    void mapsProviderUnavailableResponseWithoutRetrying() {
        respondWithError(503);

        AiCallResult<LlmCompletion> result = llmProvider.complete(requestPayload());

        assertFailureType(result, AiFailureType.PROVIDER_UNAVAILABLE);
        verifyCalledOnce();
    }

    @Test
    void mapsAuthenticationErrorWithoutRetrying() {
        respondWithError(401);

        AiCallResult<LlmCompletion> result = llmProvider.complete(requestPayload());

        assertFailureType(result, AiFailureType.AUTHENTICATION_ERROR);
        assertThat(((AiCallResult.Failure<LlmCompletion>) result).failure().retryable()).isFalse();
        verifyCalledOnce();
    }

    @Test
    void mapsBlankCompletionToEmptyOutput() {
        llmMock("/v1/chat/completions")
                .withProvider(OPENAI)
                .withModel("test-model")
                .respondingWith(completion().withText("").withStopReason("stop"))
                .applyTo(mockServerClient);

        AiCallResult<LlmCompletion> result = llmProvider.complete(requestPayload());

        assertFailureType(result, AiFailureType.EMPTY_OUTPUT);
        verifyCalledOnce();
    }

    @Test
    void mapsMalformedProviderResponse() {
        mockServerClient
                .when(request().withMethod("POST").withPath("/v1/chat/completions"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                                .withBody("{\"unexpected\":true}"));

        AiCallResult<LlmCompletion> result = llmProvider.complete(requestPayload());

        assertFailureType(result, AiFailureType.PROVIDER_RESPONSE_ERROR);
        verifyCalledOnce();
    }

    private void respondWithError(int statusCode) {
        mockServerClient
                .when(request().withMethod("POST").withPath("/v1/chat/completions"))
                .respond(
                        response()
                                .withStatusCode(statusCode)
                                .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                                .withBody("{\"error\":{\"message\":\"mock failure\"}}"));
    }

    private void verifyCalledOnce() {
        mockServerClient.verify(
                request().withMethod("POST").withPath("/v1/chat/completions"),
                org.mockserver.verify.VerificationTimes.exactly(1));
    }

    private static void assertFailureType(
            AiCallResult<LlmCompletion> result, AiFailureType expectedType) {
        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        AiCallResult.Failure<LlmCompletion> failure = (AiCallResult.Failure<LlmCompletion>) result;
        assertThat(failure.failure().type())
                .as("provider error code: %s", failure.failure().providerErrorCode())
                .isEqualTo(expectedType);
    }

    private static LlmRequest requestPayload() {
        AiTraceContext traceContext =
                new AiTraceContext(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        1,
                        UUID.fromString("00000000-0000-0000-0000-000000000006"),
                        UUID.fromString("00000000-0000-0000-0000-000000000007"),
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        UUID.fromString("00000000-0000-0000-0000-000000000009"));
        return new LlmRequest(
                traceContext,
                new AiAttemptContext(1, 0, 0),
                AiOperation.CAUSE_ANALYSIS,
                1,
                "Return only JSON.",
                "안전하게 정제된 표준 발화",
                "prompt-v1",
                "schema-v1",
                "policy-v1");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                FlywayAutoConfiguration.class
            })
    @Import(AiProviderConfiguration.class)
    static class TestApplication {}
}
