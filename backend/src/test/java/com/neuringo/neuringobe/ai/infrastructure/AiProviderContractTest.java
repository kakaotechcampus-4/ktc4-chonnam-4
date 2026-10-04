package com.neuringo.neuringobe.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.client.LlmMockBuilder.llmMock;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.Provider.OPENAI;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.config.AiProviderConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.MediaType;
import org.mockserver.testcontainers.MockServerContainer;
import org.mockserver.verify.VerificationTimes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 제공자 공통 호출 계약(backend/docs/ai-provider-contract.md)을 OpenAI 호환 HTTP 수준에서 확인한다. 기능 테스트(진미나 님
 * MockServerLlmIntegrationTest)가 "성공·타임아웃·형식 오류·제공자 오류를 구분하는지"를 보고, 여기서는 문서의 약속을 항목별로 본다.
 *
 * <ul>
 *   <li>"추적 문맥은 … 프롬프트에 자동으로 추가하지 않는다": 요청 본문에는 시스템·사용자 메시지만 있고 아동·학급·세션 ID 가 없다.
 *   <li>"실패 분류" 표: HTTP 상태별 실패 유형과 재시도 가능 여부가 표와 같다. 401·403 과 그 밖의 4xx(PROVIDER_REQUEST_REJECTED)는
 *       재시도하지 않고, 429·5xx·시간 초과·빈 결과·해석할 수 없는 응답(PROVIDER_RESPONSE_ERROR)은 재시도할 수 있다.
 *   <li>"자동 재시도는 0회": 실패해도 제공자를 한 번만 부른다.
 *   <li>"제공자 오류 본문과 예외 메시지를 실패 결과에 포함하지 않는다", "전체 프롬프트와 completion 을 운영 로그에 남기지 않는다".
 * </ul>
 *
 * <p>실제 AI API·API Key 는 쓰지 않는다. AI 응답 내용은 단정하지 않는다.
 */
@SpringBootTest(classes = AiProviderContractTest.TestApplication.class)
@ExtendWith(OutputCaptureExtension.class)
class AiProviderContractTest {

    private static final MockServerContainer MOCK_SERVER = new MockServerContainer();
    private static final String COMPLETIONS = "/v1/chat/completions";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM_PROMPT = "Return only JSON.";
    private static final String USER_PROMPT = "안전하게 정제된 표준 발화";

    static {
        MOCK_SERVER.start();
    }

    @Autowired private LlmProvider llmProvider;

    private MockServerClient mockServer;

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
        mockServer = MOCK_SERVER.getClient();
        mockServer.reset();
    }

    @Test
    void sendsOnlyThePromptsAndNoTraceIdentifiers() {
        respondWithCompletion("{\"learning_state\":\"PARTIAL_UNDERSTANDING\"}");
        AiTraceContext trace = traceContext();

        AiCallResult<LlmCompletion> result = llmProvider.complete(llmRequest(trace, USER_PROMPT));

        assertThat(result).isInstanceOf(AiCallResult.Success.class);
        HttpRequest[] sent = mockServer.retrieveRecordedRequests(request().withPath(COMPLETIONS));
        assertThat(sent).hasSize(1);
        String body = sent[0].getBodyAsString();
        JsonNode messages = JSON.readTree(body).get("messages");
        assertThat(messages.values().stream().map(m -> m.get("role").asString()).toList())
                .containsExactly("system", "user");
        assertThat(messages.values().stream().map(m -> m.get("content").asString()).toList())
                .containsExactly(SYSTEM_PROMPT, USER_PROMPT);
        assertThat(body).doesNotContain(traceIdsOf(trace));
    }

    @ParameterizedTest(name = "HTTP {0} → {1}, 재시도 가능 {2}")
    @CsvSource({
        "400, PROVIDER_REQUEST_REJECTED, false",
        "401, AUTHENTICATION_ERROR, false",
        "403, AUTHENTICATION_ERROR, false",
        "404, PROVIDER_REQUEST_REJECTED, false",
        "409, PROVIDER_REQUEST_REJECTED, false",
        "422, PROVIDER_REQUEST_REJECTED, false",
        "429, RATE_LIMITED, true",
        "500, PROVIDER_UNAVAILABLE, true",
        "502, PROVIDER_UNAVAILABLE, true",
        "503, PROVIDER_UNAVAILABLE, true",
        "504, PROVIDER_UNAVAILABLE, true"
    })
    void classifiesProviderStatusesAsTheContractTable(
            int status, AiFailureType type, boolean retryable) {
        String providerMessage = "provider-secret-" + UUID.randomUUID();
        mockServer
                .when(request().withMethod("POST").withPath(COMPLETIONS))
                .respond(
                        response()
                                .withStatusCode(status)
                                .withContentType(MediaType.APPLICATION_JSON)
                                .withBody("{\"error\":{\"message\":\"" + providerMessage + "\"}}"));

        AiFailure failure =
                failureOf(llmProvider.complete(llmRequest(traceContext(), USER_PROMPT)));

        assertThat(failure.type()).isEqualTo(type);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(String.valueOf(failure.providerErrorCode())).doesNotContain(providerMessage);
        verifyCalledOnce();
    }

    @Test
    void timeoutIsRetryableAndCalledOnce() {
        mockServer
                .when(request().withMethod("POST").withPath(COMPLETIONS))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withDelay(Delay.seconds(1))
                                .withContentType(MediaType.APPLICATION_JSON)
                                .withBody("{}"));

        AiFailure failure =
                failureOf(llmProvider.complete(llmRequest(traceContext(), USER_PROMPT)));

        assertThat(failure.type()).isEqualTo(AiFailureType.TIMEOUT);
        assertThat(failure.retryable()).isTrue();
        verifyCalledOnce();
    }

    @Test
    void unreadableProviderResponseIsRetryableResponseError() {
        mockServer
                .when(request().withMethod("POST").withPath(COMPLETIONS))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withContentType(MediaType.APPLICATION_JSON)
                                .withBody("{\"choices\": [ this is not json"));

        AiFailure failure =
                failureOf(llmProvider.complete(llmRequest(traceContext(), USER_PROMPT)));

        assertThat(failure.type()).isEqualTo(AiFailureType.PROVIDER_RESPONSE_ERROR);
        assertThat(failure.retryable()).isTrue();
        verifyCalledOnce();
    }

    @Test
    void whitespaceOnlyCompletionIsRetryableEmptyOutput() {
        respondWithCompletion("  \n\t ");

        AiFailure failure =
                failureOf(llmProvider.complete(llmRequest(traceContext(), USER_PROMPT)));

        assertThat(failure.type()).isEqualTo(AiFailureType.EMPTY_OUTPUT);
        assertThat(failure.retryable()).isTrue();
        verifyCalledOnce();
    }

    @Test
    void keepsPromptAndCompletionOutOfLogs(CapturedOutput output) {
        String marker = "utterance-" + UUID.randomUUID();
        respondWithCompletion("{\"text\":\"" + marker + "\"}");

        AiCallResult<LlmCompletion> result =
                llmProvider.complete(llmRequest(traceContext(), USER_PROMPT + " " + marker));

        assertThat(result).isInstanceOf(AiCallResult.Success.class);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void keepsProviderErrorBodyOutOfLogs(CapturedOutput output) {
        String marker = "provider-error-" + UUID.randomUUID();
        mockServer
                .when(request().withMethod("POST").withPath(COMPLETIONS))
                .respond(
                        response()
                                .withStatusCode(503)
                                .withContentType(MediaType.APPLICATION_JSON)
                                .withBody("{\"error\":{\"message\":\"" + marker + "\"}}"));

        failureOf(llmProvider.complete(llmRequest(traceContext(), USER_PROMPT)));

        assertThat(output.getAll()).doesNotContain(marker);
    }

    private void respondWithCompletion(String text) {
        llmMock(COMPLETIONS)
                .withProvider(OPENAI)
                .withModel("test-model")
                .respondingWith(completion().withText(text).withStopReason("stop"))
                .applyTo(mockServer);
    }

    private void verifyCalledOnce() {
        mockServer.verify(
                request().withMethod("POST").withPath(COMPLETIONS), VerificationTimes.exactly(1));
    }

    private static AiFailure failureOf(AiCallResult<LlmCompletion> result) {
        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        return ((AiCallResult.Failure<LlmCompletion>) result).failure();
    }

    /** 문서의 추적 문맥 10개를 모두 채운다. 하나라도 요청 본문에 실리면 실패한다. */
    private static AiTraceContext traceContext() {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    private static List<String> traceIdsOf(AiTraceContext trace) {
        return Stream.of(
                        trace.requestId(),
                        trace.activityId(),
                        trace.classId(),
                        trace.childId(),
                        trace.scenarioId(),
                        trace.sessionId(),
                        trace.turnId(),
                        trace.analysisId(),
                        trace.candidateId())
                .map(UUID::toString)
                .toList();
    }

    private static LlmRequest llmRequest(AiTraceContext trace, String userPrompt) {
        return new LlmRequest(
                trace,
                new AiAttemptContext(1, 0, 0),
                AiOperation.CAUSE_ANALYSIS,
                1,
                SYSTEM_PROMPT,
                userPrompt,
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
