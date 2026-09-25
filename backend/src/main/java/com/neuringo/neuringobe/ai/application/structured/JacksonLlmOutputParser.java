package com.neuringo.neuringobe.ai.application.structured;

import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class JacksonLlmOutputParser<T> implements LlmOutputParser<T> {

    private final ObjectMapper objectMapper;
    private final Class<T> outputType;

    public JacksonLlmOutputParser(ObjectMapper objectMapper, Class<T> outputType) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.outputType = Objects.requireNonNull(outputType);
    }

    @Override
    public T parse(String content) {
        try {
            return objectMapper.readValue(content, outputType);
        } catch (RuntimeException exception) {
            throw new InvalidLlmOutputException(
                    "LLM output does not satisfy the required structure", exception);
        }
    }
}
