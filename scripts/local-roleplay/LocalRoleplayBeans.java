package com.neuringo.neuringobe.localroleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 로컬 대화 확인 전용 부품. 운영 코드에 아직 구현이 없는 자리를 채운다. 배포에 넣지 않는다.
 *
 * <ul>
 *   <li>입력 처리: 안전 검사(SAFE) 없이 앞뒤 공백만 지우고 통과시킨다.
 *   <li>STT: 업로드한 "음성" 파일 내용을 UTF-8 글자로 읽는다. 녹음 없이 문장으로 대화를 확인한다.
 *   <li>TTS: 끈다. 대답은 글자로만 돌아온다.
 *   <li>음성 전달: TTS 를 껐으므로 쓰이지 않는다.
 *   <li>LOCAL_LLM_TRACE 에 파일 경로가 있으면 LLM 요청 종류와 출력을 그 파일에 남긴다. 운영은 개인정보 때문에 남기지 않으므로 로컬 합성
 *       데이터로만 쓴다.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class LocalRoleplayBeans {

    @Bean
    RoleplayInputProcessor localInputProcessor() {
        return text ->
                text == null || text.isBlank()
                        ? new RoleplayInputProcessor.Invalid()
                        : new RoleplayInputProcessor.Accepted(text.strip());
    }

    @Bean
    SpeechToTextProvider localTextStt() {
        return request ->
                new AiCallResult.Success<>(
                        new SpeechTranscription(
                                new String(request.audio(), StandardCharsets.UTF_8).strip(),
                                0.99,
                                "local-text",
                                "local-text",
                                null,
                                null),
                        metadata(AiOperation.SPEECH_TRANSCRIPTION, request.currentAttempt()));
    }

    @Bean
    TextToSpeechProvider localNoTts() {
        return request ->
                new AiCallResult.Failure<>(
                        new AiFailure(AiFailureType.PROVIDER_REQUEST_REJECTED, "local-tts-off"),
                        metadata(AiOperation.SPEECH_SYNTHESIS, request.currentAttempt()));
    }

    @Bean
    RoleplaySpeechDelivery localNoSpeechDelivery() {
        return (candidateId, speech) -> Optional.empty();
    }

    @Bean
    static BeanPostProcessor localLlmTrace() {
        String file = System.getenv("LOCAL_LLM_TRACE");
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (file == null || file.isBlank() || !(bean instanceof LlmProvider provider)) {
                    return bean;
                }
                return (LlmProvider)
                        request -> {
                            var result = provider.complete(request);
                            String output =
                                    switch (result) {
                                        case AiCallResult.Success<LlmCompletion> success ->
                                                success.data().content();
                                        case AiCallResult.Failure<LlmCompletion> failure ->
                                                "FAILURE " + failure.failure();
                                    };
                            append(
                                    Path.of(file),
                                    "### "
                                            + Instant.now()
                                            + " "
                                            + request.operation()
                                            + " attempt="
                                            + request.currentAttempt()
                                            + "\n--- input\n"
                                            + request.userPrompt()
                                            + "\n--- output\n"
                                            + output
                                            + "\n\n");
                            return result;
                        };
            }
        };
    }

    private static synchronized void append(Path file, String text) {
        try {
            Files.writeString(
                    file,
                    text,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static AiCallMetadata metadata(AiOperation operation, int attempt) {
        return new AiCallMetadata(
                UUID.randomUUID(), operation, "local", "local", null, null, null, 0, attempt, null,
                null, null);
    }
}
