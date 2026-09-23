package com.neuringo.neuringobe.ai.application.structured;

public interface LlmOutputParser<T> {

    T parse(String content);
}
