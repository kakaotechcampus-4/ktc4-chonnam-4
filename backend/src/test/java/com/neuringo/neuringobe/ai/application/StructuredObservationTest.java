package com.neuringo.neuringobe.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.structured.*;
import com.neuringo.neuringobe.ai.infrastructure.springai.CallLoggingLlmProvider;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class StructuredObservationTest {
    private final Logger executorLogger =
            (Logger) LoggerFactory.getLogger(StructuredLlmExecutor.class);
    private Level previousLevel;

    @BeforeEach
    void enableDebugLogging() {
        previousLevel = executorLogger.getLevel();
        executorLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLogging() {
        executorLogger.setLevel(previousLevel);
    }

    @Test
    void distinguishesProviderSuccessFromValidationFailureWithoutLoggingContent(
            CapturedOutput output) {
        UUID id = UUID.randomUUID();
        var trace = new AiTraceContext(id, id, null, null, null, null, id, id, id, id);
        var request =
                new LlmRequest(
                        trace,
                        new AiAttemptContext(1, 1, 1),
                        AiOperation.RESPONSE_EVALUATION,
                        1,
                        "비밀 프롬프트 원문",
                        "비밀 아동 발화",
                        "prompt-test",
                        "schema-test",
                        null);
        var meta =
                new AiCallMetadata(
                        id,
                        request.operation(),
                        "test",
                        "test-model",
                        "prompt-test",
                        "schema-test",
                        null,
                        7,
                        1,
                        10,
                        2,
                        "STOP");
        var provider =
                new CallLoggingLlmProvider(
                        r ->
                                new AiCallResult.Success<>(
                                        new LlmCompletion(
                                                "비밀 모델 출력", "test", "test-model", "STOP", 10, 2),
                                        meta));
        var executor = new StructuredLlmExecutor(provider, true);
        var result =
                executor.execute(
                        request,
                        raw -> {
                            throw new InvalidLlmOutputException("비밀 오류 원문", null);
                        });
        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        assertThat(output)
                .contains(
                        "LLM_CALL request=" + id,
                        "outcome=OK",
                        "schema=schema-test",
                        "LLM_VALIDATION request=" + id,
                        "outcome=INVALID_OUTPUT_FORMAT");
        assertThat(output).doesNotContain("비밀 프롬프트 원문", "비밀 아동 발화", "비밀 모델 출력", "비밀 오류 원문");
    }
}
