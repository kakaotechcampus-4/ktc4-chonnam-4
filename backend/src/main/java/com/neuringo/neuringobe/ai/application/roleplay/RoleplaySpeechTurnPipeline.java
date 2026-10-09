package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.RecoveryReason;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.Objects;

/**
 * Use through RoleplayTurnRunner/Responder with one deadline started before STT. No storage here.
 */
public final class RoleplaySpeechTurnPipeline {
    private static final int MAX_ATTEMPTS = 3;
    private final SpeechToTextProvider stt;
    private final TextToSpeechProvider tts;
    private final RoleplayInputProcessor inputProcessor;
    private final RoleplayPromptFactory prompts;
    private final StructuredLlmExecutor llm;
    private final RetryingRoleplayTurnExecutor turns;

    public RoleplaySpeechTurnPipeline(
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplayInputProcessor inputProcessor,
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm) {
        this(stt, tts, inputProcessor, prompts, llm, RoleplayRetryMetrics.NONE);
    }

    public RoleplaySpeechTurnPipeline(
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplayInputProcessor inputProcessor,
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm,
            RoleplayRetryMetrics metrics) {
        this.stt = Objects.requireNonNull(stt);
        this.tts = Objects.requireNonNull(tts);
        this.inputProcessor = Objects.requireNonNull(inputProcessor);
        this.prompts = Objects.requireNonNull(prompts);
        this.llm = Objects.requireNonNull(llm);
        this.turns = new RetryingRoleplayTurnExecutor(java.util.UUID::randomUUID, metrics);
    }

    /** Context's learner text is replaced; all scenario/state/dialogue values remain the same. */
    public RoleplayTurnResult execute(
            SpeechTranscriptionRequest audio,
            RoleplayTurnInput context,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(audio);
        Objects.requireNonNull(context);
        Objects.requireNonNull(deadline);
        if (audio.currentAttempt() != 1
                || !audio.traceContext().turnId().equals(context.learnerTurn().turnId())) {
            throw new IllegalArgumentException(
                    "A new voice pipeline must start at attempt 1 on the same turn");
        }
        return switch (transcribe(audio, deadline)) {
            case AiCallResult.Success<SpeechTranscription> success ->
                    processTranscription(success.data(), audio, context, deadline);
            case AiCallResult.Failure<SpeechTranscription> ignored ->
                    rejected(InputFailure.TRANSCRIPTION_FAILED);
        };
    }

    private RoleplayTurnResult processTranscription(
            SpeechTranscription transcription,
            SpeechTranscriptionRequest audio,
            RoleplayTurnInput context,
            RoleplayTurnDeadline deadline) {
        if (!transcription.hasSpeech()) return rejected(InputFailure.NO_SPEECH);
        Double confidence = transcription.confidence();
        if (confidence == null || !Double.isFinite(confidence))
            return rejected(InputFailure.UNKNOWN_CONFIDENCE);
        if (confidence <= 0.40) return rejected(InputFailure.LOW_CONFIDENCE);
        return switch (deadline.withinBudget(() -> inputProcessor.process(transcription.text()))) {
            case RoleplayInputProcessor.Unsafe ignored ->
                    new RoleplayTurnResult.RecoveryRequired(
                            RetryTarget.SAFETY_ESCALATION,
                            AiOperation.SPEECH_TRANSCRIPTION,
                            RecoveryReason.INPUT_SAFETY_REJECTED);
            case RoleplayInputProcessor.Invalid ignored -> rejected(InputFailure.INVALID_INPUT);
            case RoleplayInputProcessor.Accepted accepted ->
                    generateResponse(accepted.canonicalUtterance(), audio, context, deadline);
        };
    }

    private RoleplayTurnResult generateResponse(
            String canonical,
            SpeechTranscriptionRequest audio,
            RoleplayTurnInput context,
            RoleplayTurnDeadline deadline) {
        var input =
                new RoleplayTurnInput(
                        context.scenario(),
                        context.state(),
                        new RoleplayTurnInput.LearnerTurn(
                                context.learnerTurn().turnId(), canonical),
                        context.recentDialogue());
        var steps = new StructuredRoleplayTurnSteps(prompts, llm, audio.traceContext(), input);
        var result = turns.execute(input.learnerTurn().turnId(), steps, deadline);
        if (!(result instanceof RoleplayTurnResult.Ready ready)) return result;
        var trace = audio.traceContext();
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
        return new RoleplayTurnResult.SpokenReady(ready, canonical, speech);
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
