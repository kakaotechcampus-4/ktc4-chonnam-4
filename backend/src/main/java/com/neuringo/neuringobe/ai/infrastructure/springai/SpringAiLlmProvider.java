package com.neuringo.neuringobe.ai.infrastructure.springai;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

public final class SpringAiLlmProvider implements LlmProvider {

    private final ChatModel chatModel;
    private final SpringAiFailureMapper failureMapper;
    private final String providerName;
    private final Duration requestTimeout;
    private final int maxRetries;

    public SpringAiLlmProvider(
            ChatModel chatModel,
            SpringAiFailureMapper failureMapper,
            String providerName,
            Duration requestTimeout,
            int maxRetries) {
        this.chatModel = Objects.requireNonNull(chatModel);
        this.failureMapper = Objects.requireNonNull(failureMapper);
        this.providerName = Objects.requireNonNull(providerName);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.maxRetries = maxRetries;
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        long startedAt = System.nanoTime();

        try {
            Prompt prompt =
                    new Prompt(
                            List.of(
                                    new SystemMessage(request.systemPrompt()),
                                    new UserMessage(request.userPrompt())),
                            OpenAiChatOptions.builder()
                                    .timeout(requestTimeout)
                                    .maxRetries(maxRetries)
                                    .build());
            ChatResponse response = chatModel.call(prompt);
            var result = response == null ? null : response.getResult();
            var responseMetadata = response == null ? null : response.getMetadata();
            String content =
                    result == null || result.getOutput() == null
                            ? null
                            : result.getOutput().getText();
            String model = responseMetadata == null ? null : responseMetadata.getModel();
            String finishReason =
                    result == null || result.getMetadata() == null
                            ? null
                            : result.getMetadata().getFinishReason();
            Integer inputTokens =
                    responseMetadata == null || responseMetadata.getUsage() == null
                            ? null
                            : responseMetadata.getUsage().getPromptTokens();
            Integer outputTokens =
                    responseMetadata == null || responseMetadata.getUsage() == null
                            ? null
                            : responseMetadata.getUsage().getCompletionTokens();
            AiCallMetadata metadata =
                    metadata(request, startedAt, model, inputTokens, outputTokens, finishReason);

            if (content == null || content.isBlank()) {
                AiFailure failure = new AiFailure(AiFailureType.EMPTY_OUTPUT, true, null);
                return new AiCallResult.Failure<>(failure, metadata);
            }

            LlmCompletion completion =
                    new LlmCompletion(
                            content, providerName, model, finishReason, inputTokens, outputTokens);
            return new AiCallResult.Success<>(completion, metadata);
        } catch (RuntimeException exception) {
            AiFailure failure = failureMapper.map(exception);
            return new AiCallResult.Failure<>(
                    failure, metadata(request, startedAt, null, null, null, null));
        }
    }

    private AiCallMetadata metadata(
            LlmRequest request,
            long startedAt,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            String finishReason) {
        return new AiCallMetadata(
                request.traceContext().requestId(),
                request.operation(),
                providerName,
                model,
                request.promptVersion(),
                request.responseSchemaVersion(),
                request.policyVersion(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                request.currentAttempt(),
                inputTokens,
                outputTokens,
                finishReason);
    }
}
