package com.neuringo.neuringobe.ai.application.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** classpath 의 prompts/ 아래 프롬프트 파일을 읽는다. 파일 하나가 프롬프트 버전 하나다. */
final class PromptTemplates {

    private PromptTemplates() {}

    static String load(String path) {
        String resource = "prompts/" + path;
        try (InputStream input =
                PromptTemplates.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("prompt template not found: " + resource);
            }
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (content.isEmpty()) {
                throw new IllegalStateException("prompt template is empty: " + resource);
            }
            return content;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
