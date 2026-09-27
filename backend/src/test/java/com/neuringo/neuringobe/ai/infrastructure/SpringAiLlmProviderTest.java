package com.neuringo.neuringobe.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

class SpringAiLlmProviderTest {

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

    private SpringAiLlmProvider provider(ChatModel chatModel) {
        return new SpringAiLlmProvider(
                chatModel,
                new OpenAiFailureClassifier(),
                "test-provider",
                Duration.ofSeconds(10),
                0);
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
