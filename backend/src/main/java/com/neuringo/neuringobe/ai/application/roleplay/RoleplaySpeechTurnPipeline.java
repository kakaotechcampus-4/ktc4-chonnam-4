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

/** 같은 요청 기한 안에서 입력 처리·분석·생성·검수·음성 생성을 수행한다. 저장은 호출자가 맡는다. */
public final class RoleplaySpeechTurnPipeline {
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
            case AiCallResult.Failure<SpeechTranscription> ignored ->
                    rejected(InputFailure.TRANSCRIPTION_FAILED);
        };
    }

    private RoleplayTurnResult processTranscription(
            SpeechTranscription transcription,
            SpeechTranscriptionRequest audio,
            RoleplayTurnInput context,
            GoalObservationInput observations,
            RoleplayTurnDeadline deadline,
            RoleplayTurnInput.InputRecoverySnapshot recoverySnapshot) {
        if (!transcription.hasSpeech()) return rejected(InputFailure.NO_SPEECH);
        Double confidence = transcription.confidence();
        if (confidence == null || !Double.isFinite(confidence))
            return rejected(InputFailure.UNKNOWN_CONFIDENCE);
        if (confidence <= sttConfidenceThreshold)
            return new RoleplayTurnResult.InputRejected(
                    InputFailure.LOW_CONFIDENCE,
                    new RoleplayTurnResult.SttConfidenceEvidence(
                            confidence, sttConfidenceThreshold));
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
        return switch (inspectInput(text, deadline)) {
            case RoleplayInputProcessor.Unsafe ignored -> new RoleplayTurnResult.InputUnsafe();
            case RoleplayInputProcessor.Invalid ignored -> rejected(InputFailure.INVALID_INPUT);
            case RoleplayInputProcessor.Unavailable ignored ->
                    new RoleplayTurnResult.InputCheckUnavailable();
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

    private RoleplayInputProcessor.Result inspectInput(String text, RoleplayTurnDeadline deadline) {
        try {
            var result = deadline.withinBudget(() -> inputProcessor.process(text));
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
                    if (!failed.failure().retryable() || attempt == MAX_ATTEMPTS) return null;
                }
            }
        }
    }

    private static RoleplayTurnResult rejected(InputFailure failure) {
        return new RoleplayTurnResult.InputRejected(failure);
    }
}
