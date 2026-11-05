package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplaySpeechTurnPipeline;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import com.neuringo.neuringobe.roleplay.config.RoleplayInputProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 ML API 통합 실측({@link RealMlApiRoleplayIntegrationTest}) 전용 설정.
 *
 * <p>운영 구현이 아직 없는 두 빈(입력 처리기·음성 전달)과 키가 없는 TTS 만 대신한다. LLM·STT 는 실제 제공자(엘리스)를 그대로 쓰고, 호출마다
 * 단계·시도·지연·토큰·finish_reason·실패 유형을 남기려고 감싼다. 원인 분석·응답 생성·평가, 시나리오 문맥, 목표 판정은 운영 코드 그대로다.
 */
@TestConfiguration(proxyBeanMethods = false)
class RealMlApiTestConfiguration {

    /** 실측 중 남긴 제공자 호출. 출력 원문은 build 폴더 보고서에만 쓴다. */
    static final class CallLog {
        record LlmCall(
                int seq,
                AiOperation operation,
                int attempt,
                String promptVersion,
                long wallMs,
                Integer inputTokens,
                Integer outputTokens,
                String finishReason,
                String model,
                AiFailureType failureType,
                String providerErrorCode,
                String content) {}

        record SttCall(
                int seq,
                long wallMs,
                String text,
                Double confidence,
                String model,
                AiFailureType failureType,
                String providerErrorCode) {}

        private final List<LlmCall> llm = new ArrayList<>();
        private final List<SttCall> stt = new ArrayList<>();

        synchronized void add(LlmCall call) {
            llm.add(call);
        }

        synchronized void add(SttCall call) {
            stt.add(call);
        }

        synchronized int llmSize() {
            return llm.size();
        }

        synchronized int sttSize() {
            return stt.size();
        }

        synchronized List<LlmCall> llmSince(int from) {
            return List.copyOf(llm.subList(from, llm.size()));
        }

        synchronized List<SttCall> sttSince(int from) {
            return List.copyOf(stt.subList(from, stt.size()));
        }

        synchronized List<LlmCall> allLlm() {
            return List.copyOf(llm);
        }

        synchronized List<SttCall> sttAll() {
            return List.copyOf(stt);
        }

        private final Map<Integer, String> rejections = new HashMap<>();

        synchronized String lastContent() {
            return llm.isEmpty() ? null : llm.getLast().content();
        }

        /** 바로 앞 호출의 출력이 형식·계약 검사에 걸린 이유. 실측은 턴을 하나씩 보내므로 "바로 앞 호출"이 그 출력이다. */
        synchronized void noteRejection(String reason) {
            if (!llm.isEmpty()) rejections.put(llm.getLast().seq(), reason);
        }

        synchronized Map<Integer, String> rejections() {
            return Map.copyOf(rejections);
        }
    }

    @Bean
    CallLog mlApiCallLog() {
        return new CallLog();
    }

    /**
     * 운영 설정과 같은 파이프라인인데, 출력 검사에 걸린 이유만 남긴다. 팀 {@link StructuredLlmExecutor} 는 검사 예외의 메시지를 버리고
     * INVALID_OUTPUT_FORMAT 만 남기므로, 걸린 출력을 같은 파서로 한 번 더 읽어 예외 메시지를 받는다(결과는 바꾸지 않는다).
     */
    @Bean
    @Primary
    RoleplaySpeechTurnPipeline mlApiInstrumentedPipeline(
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplayInputProcessor inputProcessor,
            LlmProvider llm,
            JsonMapper mapper,
            RoleplayInputProperties inputProperties,
            CallLog log) {
        StructuredLlmExecutor executor = Mockito.spy(new StructuredLlmExecutor(llm));
        Mockito.doAnswer(
                        invocation -> {
                            Object result = invocation.callRealMethod();
                            if (result instanceof AiCallResult.Failure<?> failure
                                    && failure.failure().type()
                                            == AiFailureType.INVALID_OUTPUT_FORMAT) {
                                LlmOutputParser<?> parser = invocation.getArgument(1);
                                log.noteRejection(replay(parser, log.lastContent()));
                            }
                            return result;
                        })
                .when(executor)
                .execute(Mockito.any(), Mockito.any());
        return new RoleplaySpeechTurnPipeline(
                stt,
                tts,
                inputProcessor,
                new RoleplayPromptFactory(mapper, null),
                executor,
                inputProperties.sttConfidenceThreshold());
    }

    private static String replay(LlmOutputParser<?> parser, String content) {
        try {
            parser.parse(content);
            return "(다시 읽으니 통과)";
        } catch (InvalidLlmOutputException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 실제 LLM 제공자를 감싸 호출을 남긴다. 결과는 바꾸지 않는다. */
    @Bean
    @Primary
    LlmProvider recordingMlApiLlm(@Qualifier("llmProvider") LlmProvider real, CallLog log) {
        return new LlmProvider() {
            @Override
            public AiCallResult<LlmCompletion> complete(LlmRequest request) {
                long startedAt = System.nanoTime();
                AiCallResult<LlmCompletion> result = real.complete(request);
                long wallMs = (System.nanoTime() - startedAt) / 1_000_000;
                switch (result) {
                    case AiCallResult.Success<LlmCompletion> success ->
                            log.add(
                                    llmCall(
                                            log,
                                            request,
                                            wallMs,
                                            success.metadata(),
                                            null,
                                            null,
                                            success.data().content()));
                    case AiCallResult.Failure<LlmCompletion> failure ->
                            log.add(
                                    llmCall(
                                            log,
                                            request,
                                            wallMs,
                                            failure.metadata(),
                                            failure.failure().type(),
                                            failure.failure().providerErrorCode(),
                                            null));
                }
                return result;
            }
        };
    }

    private static CallLog.LlmCall llmCall(
            CallLog log,
            LlmRequest request,
            long wallMs,
            AiCallMetadata metadata,
            AiFailureType failureType,
            String providerErrorCode,
            String content) {
        return new CallLog.LlmCall(
                log.llmSize() + 1,
                request.operation(),
                request.currentAttempt(),
                request.promptVersion(),
                wallMs,
                metadata == null ? null : metadata.inputTokens(),
                metadata == null ? null : metadata.outputTokens(),
                metadata == null ? null : metadata.finishReason(),
                metadata == null ? null : metadata.model(),
                failureType,
                providerErrorCode,
                content);
    }

    /** 실제 STT 제공자(엘리스 Whisper)를 감싸 전사 결과·신뢰도·실패를 남긴다. */
    @Bean
    @Primary
    SpeechToTextProvider recordingMlApiStt(
            @Qualifier("speechToTextProvider") SpeechToTextProvider real, CallLog log) {
        return new SpeechToTextProvider() {
            @Override
            public AiCallResult<SpeechTranscription> transcribe(
                    SpeechTranscriptionRequest request) {
                long startedAt = System.nanoTime();
                AiCallResult<SpeechTranscription> result = real.transcribe(request);
                long wallMs = (System.nanoTime() - startedAt) / 1_000_000;
                switch (result) {
                    case AiCallResult.Success<SpeechTranscription> success ->
                            log.add(
                                    new CallLog.SttCall(
                                            log.sttSize() + 1,
                                            wallMs,
                                            success.data().text(),
                                            success.data().confidence(),
                                            success.data().model(),
                                            null,
                                            null));
                    case AiCallResult.Failure<SpeechTranscription> failure ->
                            log.add(
                                    new CallLog.SttCall(
                                            log.sttSize() + 1,
                                            wallMs,
                                            null,
                                            null,
                                            failure.metadata() == null
                                                    ? null
                                                    : failure.metadata().model(),
                                            failure.failure().type(),
                                            failure.failure().providerErrorCode()));
                }
                return result;
            }
        };
    }

    /**
     * 운영 입력 처리기(정제·개인정보 가림·안전 판정)가 아직 없어서 넣는 대역. 빈 입력은 거절하고, 나머지는 그대로 넘기며 판정 네 칸을 모두 "확인됨"으로
     * 채운다(계약상 판정이 비면 분석을 시작하지 않는다). 그래서 이 실측은 개인정보 가림·위험 입력 차단을 검증하지 않는다.
     */
    @Bean
    RoleplayInputProcessor mlApiPassThroughInput() {
        return text -> {
            if (text == null || text.isBlank()) return new RoleplayInputProcessor.Invalid();
            return new RoleplayInputProcessor.Accepted(
                    text.strip(), new RoleplayInputProcessor.Assessment(1.0, true, true, true));
        };
    }

    /** 운영 음성 전달 구현이 없어서 넣는 대역. 음성은 내보내지 않고 글자만 전달한다. */
    @Bean
    RoleplaySpeechDelivery mlApiTextOnlyDelivery() {
        return (approvedCandidateId, speech) -> Optional.empty();
    }

    /** TTS(Typecast) 키가 없어서 넣는 대역. 짧은 가짜 바이트를 돌려준다. 음성 전달 대역이 버리므로 아동 응답에는 음성이 없다. */
    @Bean
    TextToSpeechProvider mlApiSilentTts() {
        return request ->
                new AiCallResult.Success<>(
                        new SynthesizedSpeech(
                                new byte[] {0, 0},
                                AudioFormat.MP3,
                                "silent-test",
                                "silent-test",
                                "silent-test"),
                        new AiCallMetadata(
                                request.traceContext().requestId(),
                                AiOperation.SPEECH_SYNTHESIS,
                                "silent-test",
                                "silent-test",
                                null,
                                null,
                                null,
                                0,
                                request.currentAttempt(),
                                null,
                                null,
                                "stop"));
    }
}
