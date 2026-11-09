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
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

public final class SpringAiLlmProvider implements LlmProvider {

    private final ChatModel chatModel;
    private final OpenAiFailureClassifier failureClassifier;
    private final String providerName;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final String model;
    private final Integer maxOutputTokens;
    private final String reasoningEffort;

    public SpringAiLlmProvider(
            ChatModel chatModel,
            OpenAiFailureClassifier failureClassifier,
            String providerName,
            Duration requestTimeout,
            int maxRetries,
            String model) {
        this(
                chatModel,
                failureClassifier,
                providerName,
                requestTimeout,
                maxRetries,
                model,
                null,
                null);
    }

    /**
     * @param maxOutputTokens 출력 토큰 상한. 추론 모델은 추론 토큰도 이 상한에 들어가서, 너무 낮으면 JSON 이 중간에 잘린다
     * @param reasoningEffort 추론 강도. null 이면 보내지 않는다
     */
    public SpringAiLlmProvider(
            ChatModel chatModel,
            OpenAiFailureClassifier failureClassifier,
            String providerName,
            Duration requestTimeout,
            int maxRetries,
            String model,
            Integer maxOutputTokens,
            String reasoningEffort) {
        this.chatModel = Objects.requireNonNull(chatModel);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.providerName = Objects.requireNonNull(providerName);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.maxRetries = maxRetries;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningEffort = reasoningEffort;
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        if (request.callBudget() != null) requireSingleAttempt();
        long startedAt = System.nanoTime();

        Prompt prompt = createPrompt(request);
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException exception) {
            FailureMapping mapping =
                    failureClassifier.classify(exception).orElseThrow(() -> exception);
            return new AiCallResult.Failure<>(
                    mapping.failure(), metadata(request, startedAt, null, null, null, null));
        }

        return convertResponse(request, startedAt, response);
    }

    /**
     * Spring AI 2 sets SDK retries when constructing the client, not from per-call options. The
     * ChatModel's default options do not carry that value either: auto-configuration leaves their
     * maxRetries at the library default (3), so they cannot be used for this check. The client's
     * setting ({@code spring.ai.openai.max-retries}) is checked at startup in
     * AiProviderConfiguration.
     */
    public void requireSingleAttempt() {
        if (maxRetries != 0)
            throw new IllegalStateException(
                    "Budgeted LLM calls require SDK max retries to be zero");
    }

    private Prompt createPrompt(LlmRequest request) {
        return new Prompt(
                List.of(
                        new SystemMessage(request.systemPrompt()),
                        new UserMessage(request.userPrompt())),
                chatOptions(request));
    }

    /**
     * 호출마다 넘기는 옵션은 ChatModel 의 기본 옵션을 대체한다. 따라서 모델 이름을 여기서 함께 넣지 않으면 설정값이 아니라 Spring AI 내장 기본값이
     * 제공자로 나간다.
     */
    private OpenAiChatOptions chatOptions(LlmRequest request) {
        Duration timeout =
                request.callBudget() == null
                        ? requestTimeout
                        : request.callBudget().limit(requestTimeout);
        int retries = request.callBudget() == null ? maxRetries : 0;
        OpenAiChatOptions.Builder options =
                OpenAiChatOptions.builder().timeout(timeout).maxRetries(retries);
        if (model != null && !model.isBlank()) {
            options.model(model);
        }
        if (maxOutputTokens != null) {
            options.maxCompletionTokens(maxOutputTokens);
        }
        if (reasoningEffort != null) {
            options.reasoningEffort(reasoningEffort);
        }
        if (request.responseJsonSchema() != null) {
            var format =
                    ResponseFormat.builder()
                            .type(ResponseFormat.Type.JSON_SCHEMA)
                            .jsonSchema(request.responseJsonSchema())
                            .strict(true)
                            .build();
            options.responseFormat(format);
        }
        return options.build();
    }

    private AiCallResult<LlmCompletion> convertResponse(
            LlmRequest request, long startedAt, ChatResponse response) {
        var result = response == null ? null : response.getResult();
        var responseMetadata = response == null ? null : response.getMetadata();
        String content =
                result == null || result.getOutput() == null ? null : result.getOutput().getText();
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
            AiFailure failure = new AiFailure(AiFailureType.EMPTY_OUTPUT, null);
            return new AiCallResult.Failure<>(failure, metadata);
        }

        LlmCompletion completion =
                new LlmCompletion(
                        content, providerName, model, finishReason, inputTokens, outputTokens);
        return new AiCallResult.Success<>(completion, metadata);
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
