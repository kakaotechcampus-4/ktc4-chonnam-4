package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 같은 요청 기한 안에서 입력 처리·분석·생성·검수·음성 생성을 수행한다. 저장은 호출자가 맡는다. */
public final class RoleplaySpeechTurnPipeline {
    private static final Logger log = LoggerFactory.getLogger(RoleplaySpeechTurnPipeline.class);
    public static final double DEFAULT_STT_CONFIDENCE_THRESHOLD = 0.40;
    private static final int MAX_ATTEMPTS = 3;
    private final SpeechToTextProvider stt;
    private final TextToSpeechProvider tts;
    private final RoleplayInputProcessor inputProcessor;
    private final RoleplayPromptFactory prompts;
    private final StructuredLlmExecutor llm;
    private final double sttConfidenceThreshold;

    public RoleplaySpeechTurnPipeline(
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplayInputProcessor inputProcessor,
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm) {
        this(stt, tts, inputProcessor, prompts, llm, DEFAULT_STT_CONFIDENCE_THRESHOLD);
    }

    public RoleplaySpeechTurnPipeline(
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplayInputProcessor inputProcessor,
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm,
            double sttConfidenceThreshold) {
        this.stt = Objects.requireNonNull(stt);
        this.tts = Objects.requireNonNull(tts);
        this.inputProcessor = Objects.requireNonNull(inputProcessor);
        this.prompts = Objects.requireNonNull(prompts);
        this.llm = Objects.requireNonNull(llm);
        if (!Double.isFinite(sttConfidenceThreshold)
                || sttConfidenceThreshold < 0
                || sttConfidenceThreshold > 1)
            throw new IllegalArgumentException("STT 신뢰도 기준은 0부터 1 사이의 유한한 값이어야 합니다.");
        this.sttConfidenceThreshold = sttConfidenceThreshold;
    }

    /** 검증된 표준 발화만 교체하고 승인 기준·이전 관찰·진행 상태는 그대로 전달한다. */
    public RoleplayTurnResult execute(
            SpeechTranscriptionRequest audio,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline) {
        return execute(audio, context, observations, deadline, null);
    }

    public RoleplayTurnResult execute(
            SpeechTranscriptionRequest audio,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline,
            RoleplayTurnInput.InputRecoverySnapshot recoverySnapshot) {
        Objects.requireNonNull(audio);
        Objects.requireNonNull(context);
        Objects.requireNonNull(observations).requireMatches(context);
        Objects.requireNonNull(deadline);
        if (audio.currentAttempt() != 1
                || !audio.traceContext().turnId().equals(context.learnerTurn().turnId())) {
            throw new IllegalArgumentException(
                    "A new voice pipeline must start at attempt 1 on the same turn");
        }
        return switch (transcribe(audio, deadline)) {
            case AiCallResult.Success<SpeechTranscription> success ->
                    processTranscription(
                            success.data(),
                            audio,
                            context,
                            observations,
                            deadline,
                            recoverySnapshot);
            case AiCallResult.Failure<SpeechTranscription> failed -> {
                // 전사 원문·제공자 오류 본문은 남기지 않는다. 운영에서 "말해도 인식을 못 한다"를 가려내는 근거다.
                log.warn(
                        "음성 인식 실패: failure={}, provider={}",
                        failed.failure().type(),
                        failed.metadata() == null ? null : failed.metadata().provider());
                yield rejected(InputFailure.TRANSCRIPTION_FAILED);
            }
        };
    }

    private RoleplayTurnResult processTranscription(
            SpeechTranscription transcription,
            SpeechTranscriptionRequest audio,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline,
            RoleplayTurnInput.InputRecoverySnapshot recoverySnapshot) {
        if (!transcription.hasSpeech()) {
            log.info("음성 입력 재입력 안내: reason={}", InputFailure.NO_SPEECH);
            return rejected(InputFailure.NO_SPEECH);
        }
        Double confidence = transcription.confidence();
        if (confidence == null || !Double.isFinite(confidence)) {
            // logprob 을 돌려주지 않는 모델·중계 서버면 모든 음성 입력이 여기서 막힌다(STT_INCLUDE_LOGPROBS·STT_MODEL 확인).
            log.warn("음성 입력 재입력 안내: reason={}", InputFailure.UNKNOWN_CONFIDENCE);
            return rejected(InputFailure.UNKNOWN_CONFIDENCE);
        }
        if (confidence <= sttConfidenceThreshold) {
            log.info(
                    "음성 입력 재입력 안내: reason={}, confidence={}",
                    InputFailure.LOW_CONFIDENCE,
                    String.format("%.3f", confidence));
            return new RoleplayTurnResult.InputRejected(
                    InputFailure.LOW_CONFIDENCE,
                    new RoleplayTurnResult.SttConfidenceEvidence(
                            confidence, sttConfidenceThreshold));
        }
        if (!containsHangul(transcription.text())) {
            // language=ko 를 보내도 "응"·"음" 같은 짧은 답이 "嗯?"·"Hm."·"うん。"으로 전사되고 신뢰도가 기준을 넘기도 한다
            // (docs/ai-quality-results-2026-10-11.md). 한글이 하나도 없으면 알아듣지 못한 것으로 보고 다시 말해 달라고 한다.
            log.info(
                    "음성 입력 재입력 안내: reason={}, cause=no_hangul, confidence={}",
                    InputFailure.LOW_CONFIDENCE,
                    String.format("%.3f", confidence));
            return rejected(InputFailure.LOW_CONFIDENCE);
        }
        return processInput(
                transcription.text(),
                audio.traceContext(),
                context,
                observations,
                deadline,
                "VOICE",
                confidence,
                sttConfidenceThreshold,
                recoverySnapshot);
    }

    public RoleplayTurnResult executeText(
            AiTraceContext trace,
            String text,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline) {
        return executeText(trace, text, context, observations, deadline, null);
    }

    public RoleplayTurnResult executeText(
            AiTraceContext trace,
            String text,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline,
            RoleplayTurnInput.InputRecoverySnapshot recoverySnapshot) {
        Objects.requireNonNull(trace);
        Objects.requireNonNull(text);
        observations.requireMatches(context);
        deadline.requireActive();
        if (!Objects.equals(trace.turnId(), context.learnerTurn().turnId()))
            throw new IllegalArgumentException("Text input trace does not match the current turn");
        if (text.isBlank()) return rejected(InputFailure.INVALID_INPUT);
        return processInput(
                text, trace, context, observations, deadline, "TEXT", null, null, recoverySnapshot);
    }

    private RoleplayTurnResult processInput(
            String text,
            AiTraceContext trace,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline,
            String inputType,
            Double sttConfidence,
            Double sttThreshold,
            RoleplayTurnInput.InputRecoverySnapshot recoverySnapshot) {
        return switch (inspectInput(text, trace, deadline)) {
            case RoleplayInputProcessor.Unsafe ignored -> {
                log.warn("입력 판정: inputType={}, decision=UNSAFE", inputType);
                yield new RoleplayTurnResult.InputUnsafe();
            }
            case RoleplayInputProcessor.Invalid ignored -> {
                log.info(
                        "입력 판정 재입력 안내: inputType={}, reason={}",
                        inputType,
                        InputFailure.INVALID_INPUT);
                yield rejected(InputFailure.INVALID_INPUT);
            }
            case RoleplayInputProcessor.Unavailable ignored -> {
                log.warn("입력 판정 불가: inputType={}", inputType);
                yield new RoleplayTurnResult.InputCheckUnavailable();
            }
            case RoleplayInputProcessor.Accepted accepted ->
                    processAccepted(
                            accepted,
                            trace,
                            context,
                            observations,
                            deadline,
                            inputType,
                            sttConfidence,
                            sttThreshold,
                            recoverySnapshot);
        };
    }

    private RoleplayTurnResult processAccepted(
            RoleplayInputProcessor.Accepted accepted,
            AiTraceContext trace,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline,
            String inputType,
            Double sttConfidence,
            Double sttThreshold,
            RoleplayTurnInput.InputRecoverySnapshot recoverySnapshot) {
        var assessment = accepted.assessment();
        if (assessment == null || assessment.safe() == null)
            return new RoleplayTurnResult.InputCheckUnavailable();
        if (!assessment.safe()) return new RoleplayTurnResult.InputUnsafe();
        if (!Boolean.TRUE.equals(assessment.meaningPreserved()))
            return rejected(InputFailure.INVALID_INPUT);
        var confidence = assessment.normalizationConfidence();
        if (confidence == null
                || !Double.isFinite(confidence)
                || confidence < 0
                || confidence > 1
                || assessment.personalInformationMasked() == null)
            return new RoleplayTurnResult.InputCheckUnavailable();
        var quality =
                new RoleplayTurnInput.InputQuality(
                        inputType,
                        sttConfidence,
                        sttThreshold,
                        confidence,
                        assessment.personalInformationMasked(),
                        recoverySnapshot);
        return generateResponse(
                accepted.canonicalUtterance(), quality, trace, context, observations, deadline);
    }

    private RoleplayInputProcessor.Result inspectInput(
            String text, AiTraceContext trace, RoleplayTurnDeadline deadline) {
        try {
            var result =
                    deadline.withinBudget(
                            () ->
                                    inputProcessor.process(
                                            new RoleplayInputProcessor.Input(
                                                    text, trace, deadline)));
            return result == null ? new RoleplayInputProcessor.Unavailable() : result;
        } catch (RoleplayTurnDeadline.Expired expired) {
            throw expired;
        } catch (RuntimeException unavailable) {
            // 검사기 예외의 원문·개인정보는 복구 결과에 포함하지 않는다.
            deadline.requireActive();
            return new RoleplayInputProcessor.Unavailable();
        }
    }

    private RoleplayTurnResult generateResponse(
            String canonical,
            RoleplayTurnInput.InputQuality quality,
            AiTraceContext trace,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline) {
        var input =
                new RoleplayTurnInput(
                        context.scenario(),
                        context.state(),
                        new RoleplayTurnInput.LearnerTurn(
                                context.learnerTurn().turnId(), canonical, quality),
                        context.recentDialogue());
        var steps = new StructuredRoleplayTurnSteps(prompts, llm, trace, input, observations);
        var result =
                new RetryingRoleplayTurnExecutor()
                        .execute(input.learnerTurn().turnId(), steps, deadline);
        if (!(result instanceof RoleplayTurnResult.Ready ready)) return result;
        var synthesisTrace =
                new AiTraceContext(
                        trace.requestId(),
                        trace.activityId(),
                        trace.classId(),
                        trace.childId(),
                        trace.scenarioId(),
                        trace.scenarioVersion(),
                        trace.sessionId(),
                        trace.turnId(),
                        null,
                        ready.candidate().candidateId());
        SynthesizedSpeech speech = synthesize(synthesisTrace, ready.candidate().text(), deadline);
        return new RoleplayTurnResult.SpokenReady(ready, canonical, speech, null, null, quality);
    }

    private AiCallResult<SpeechTranscription> transcribe(
            SpeechTranscriptionRequest audio, RoleplayTurnDeadline deadline) {
        for (int attempt = 1; ; attempt++) {
            var request =
                    new SpeechTranscriptionRequest(
                            audio.traceContext(),
                            attempt,
                            audio.audio(),
                            audio.format(),
                            deadline.callBudget());
            var result = deadline.withinBudget(() -> stt.transcribe(request));
            switch (result) {
                case AiCallResult.Success<SpeechTranscription> success -> {
                    return success;
                }
                case AiCallResult.Failure<SpeechTranscription> failed -> {
                    if (!failed.failure().retryable() || attempt == MAX_ATTEMPTS) return failed;
                }
            }
        }
    }

    private SynthesizedSpeech synthesize(
            AiTraceContext trace, String text, RoleplayTurnDeadline deadline) {
        for (int attempt = 1; ; attempt++) {
            var request =
                    new SpeechSynthesisRequest(
                            trace, attempt, text, null, null, deadline.callBudget());
            var result = deadline.withinBudget(() -> tts.synthesize(request));
            switch (result) {
                case AiCallResult.Success<SynthesizedSpeech> success -> {
                    return success.data();
                }
                case AiCallResult.Failure<SynthesizedSpeech> failed -> {
                    if (!failed.failure().retryable() || attempt == MAX_ATTEMPTS) {
                        // 음성 없이 글만 간다. 키 차단(403)·크레딧 부족(402) 등은 failure 로 구분한다.
                        log.warn(
                                "음성 합성 실패, 글만 보냄: failure={}, attempts={}",
                                failed.failure().type(),
                                attempt);
                        return null;
                    }
                }
            }
        }
    }

    /** 한글 음절·자모가 하나라도 있는지. 숫자·영어가 섞인 한국어 답("2개요", "OK 좋아")은 통과한다. */
    static boolean containsHangul(String text) {
        return text != null
                && text.codePoints()
                        .anyMatch(
                                c ->
                                        Character.UnicodeScript.of(c)
                                                == Character.UnicodeScript.HANGUL);
    }

    private static RoleplayTurnResult rejected(InputFailure failure) {
        return new RoleplayTurnResult.InputRejected(failure);
    }
}
