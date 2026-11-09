package com.neuringo.neuringobe.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import com.neuringo.neuringobe.ai.infrastructure.springai.SpringAiLlmProvider;
import com.openai.errors.OpenAIIoException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiLlmProviderTest {

    @Test
    void sendsStrictSchemaOverTheWireExactlyOnceAndKeepsUsage() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var received = new AtomicReference<tools.jackson.databind.JsonNode>();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    count.incrementAndGet();
                    received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                    var body =
                            """
                    {"id":"test","object":"chat.completion","created":1,"model":"test-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"valid\\":true}"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
                    """
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var model =
                    OpenAiChatModel.builder()
                            .options(
                                    OpenAiChatOptions.builder()
                                            .apiKey("test-key")
                                            .baseUrl(
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/v1")
                                            .maxRetries(0)
                                            .build())
                            .build();
            String schema =
                    "{\"type\":\"object\",\"properties\":{\"valid\":{\"type\":\"boolean\"}},\"required\":[\"valid\"],\"additionalProperties\":false}";
            var request =
                    request()
                            .withResponseJsonSchema(schema)
                            .withCallBudget(
                                    new com.neuringo.neuringobe.ai.application.model.AiCallBudget(
                                            () -> Duration.ofSeconds(5)));
            var result = (AiCallResult.Success<LlmCompletion>) provider(model).complete(request);
            assertThat(count.get()).isEqualTo(1);
            assertThat(received.get().path("response_format").path("type").asString())
                    .isEqualTo("json_schema");
            var actual = received.get().path("response_format").path("json_schema");
            assertThat(actual.path("strict").asBoolean()).isTrue();
            assertThat(actual.path("schema")).isEqualTo(mapper.readTree(schema));
            assertThat(received.get().path("model").asString()).isEqualTo("test-model");
            assertThat(result.metadata().inputTokens()).isEqualTo(11);
            assertThat(result.metadata().outputTokens()).isEqualTo(7);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestsWithoutASchemaKeepTheExistingOptions() {
        var seen = new AtomicReference<OpenAiChatOptions>();
        provider(
                        prompt -> {
                            seen.set((OpenAiChatOptions) prompt.getOptions());
                            return new ChatResponse(List.of());
                        })
                .complete(request());
        assertThat(seen.get().getResponseFormat()).isNull();
    }

    @Test
    void convertsKnownProviderExceptionToFailureResult() {
        ChatModel chatModel =
                prompt -> {
                    throw new OpenAIIoException("connection failed");
                };
        SpringAiLlmProvider provider = provider(chatModel);

        AiCallResult<LlmCompletion> result = provider.complete(request());

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        AiCallResult.Failure<LlmCompletion> failure = (AiCallResult.Failure<LlmCompletion>) result;
        assertThat(failure.failure().type()).isEqualTo(AiFailureType.NETWORK_ERROR);
    }

    @Test
    void propagatesUnclassifiedRuntimeException() {
        IllegalStateException internalError = new IllegalStateException("internal bug");
        ChatModel chatModel =
                prompt -> {
                    throw internalError;
                };
        SpringAiLlmProvider provider = provider(chatModel);

        assertThatThrownBy(() -> provider.complete(request())).isSameAs(internalError);
    }

    @Test
    void sendsOutputLimitAndReasoningEffortOnlyWhenConfigured() {
        AtomicReference<Object> sent = new AtomicReference<>();
        IllegalStateException stop = new IllegalStateException("stop after capture");
        ChatModel capture =
                prompt -> {
                    sent.set(prompt.getOptions());
                    throw stop;
                };

        SpringAiLlmProvider configured =
                new SpringAiLlmProvider(
                        capture,
                        new OpenAiFailureClassifier(),
                        "test-provider",
                        Duration.ofSeconds(10),
                        0,
                        "test-model",
                        1500,
                        "low");
        assertThatThrownBy(() -> configured.complete(request())).isSameAs(stop);
        OpenAiChatOptions options = (OpenAiChatOptions) sent.get();
        assertThat(options.getMaxCompletionTokens()).isEqualTo(1500);
        assertThat(options.getReasoningEffort()).isEqualTo("low");

        assertThatThrownBy(() -> provider(capture).complete(request())).isSameAs(stop);
        OpenAiChatOptions defaults = (OpenAiChatOptions) sent.get();
        assertThat(defaults.getMaxCompletionTokens()).isNull();
        assertThat(defaults.getReasoningEffort()).isNull();
    }

    @Test
    void propagatesResponseConversionFailureOutsideProviderBoundary() {
        IllegalStateException conversionError = new IllegalStateException("conversion bug");
        ChatResponse response =
                new ChatResponse(List.of()) {
                    @Override
                    public org.springframework.ai.chat.model.Generation getResult() {
                        throw conversionError;
                    }
                };
        SpringAiLlmProvider provider = provider(prompt -> response);

        assertThatThrownBy(() -> provider.complete(request())).isSameAs(conversionError);
    }

    @Test
    void realOpenAiChatModelPassesSingleAttemptCheckWhenRetriesAreZero() {
        // Spring AI 2 는 SDK 재시도를 클라이언트에만 넣고, ChatModel 기본 옵션의 maxRetries 는 늘 기본값 3 이다.
        // 그 값으로 검사하면 실제 OpenAI 모델에서는 재시도를 꺼도 항상 막힌다.
        SpringAiLlmProvider provider = provider(openAiChatModel(), 0);

        assertThatCode(provider::requireSingleAttempt).doesNotThrowAnyException();
    }

    @Test
    void rejectsConfiguredSdkRetries() {
        SpringAiLlmProvider provider = provider(openAiChatModel(), 1);

        assertThatThrownBy(provider::requireSingleAttempt)
                .isInstanceOf(IllegalStateException.class);
    }

    private static OpenAiChatModel openAiChatModel() {
        // 호출하지 않으므로 연결되지 않는 주소를 둔다.
        return OpenAiChatModel.builder()
                .options(
                        OpenAiChatOptions.builder()
                                .apiKey("test-key")
                                .baseUrl("http://127.0.0.1:1/v1")
                                .build())
                .build();
    }

    private SpringAiLlmProvider provider(ChatModel chatModel) {
        return provider(chatModel, 0);
    }

    private SpringAiLlmProvider provider(ChatModel chatModel, int maxRetries) {
        return new SpringAiLlmProvider(
                chatModel,
                new OpenAiFailureClassifier(),
                "test-provider",
                Duration.ofSeconds(10),
                maxRetries,
                "test-model");
    }

    private LlmRequest request() {
        AiTraceContext traceContext =
                new AiTraceContext(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        null,
                        null,
                        UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        UUID.fromString("00000000-0000-0000-0000-000000000006"),
                        null,
                        null);
        return new LlmRequest(
                traceContext,
                new AiAttemptContext(1, 0, 0),
                AiOperation.CAUSE_ANALYSIS,
                1,
                "system",
                "standard utterance",
                "prompt-v1",
                "schema-v1",
                "policy-v1");
    }
}
