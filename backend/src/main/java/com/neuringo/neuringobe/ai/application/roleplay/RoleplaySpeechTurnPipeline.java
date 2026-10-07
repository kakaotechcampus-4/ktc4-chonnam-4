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

    public RoleplaySpeechTurnPipeline(
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplayInputProcessor inputProcessor,
            RoleplayPromptFactory prompts,
            StructuredLlmExecutor llm) {
        this.stt = Objects.requireNonNull(stt);
        this.tts = Objects.requireNonNull(tts);
        this.inputProcessor = Objects.requireNonNull(inputProcessor);
        this.prompts = Objects.requireNonNull(prompts);
        this.llm = Objects.requireNonNull(llm);
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
        AiCallResult<SpeechTranscription> transcribed = transcribe(audio, deadline);
        if (transcribed instanceof AiCallResult.Failure<?>)
            return rejected(InputFailure.TRANSCRIPTION_FAILED);
        SpeechTranscription transcription =
                ((AiCallResult.Success<SpeechTranscription>) transcribed).data();
        if (!transcription.hasSpeech()) return rejected(InputFailure.NO_SPEECH);
        Double confidence = transcription.confidence();
        if (confidence == null || !Double.isFinite(confidence))
            return rejected(InputFailure.UNKNOWN_CONFIDENCE);
        if (confidence <= 0.40) return rejected(InputFailure.LOW_CONFIDENCE);
        var processed = deadline.withinBudget(() -> inputProcessor.process(transcription.text()));
        if (processed instanceof RoleplayInputProcessor.Unsafe) {
            return new RoleplayTurnResult.RecoveryRequired(
                    RetryTarget.SAFETY_ESCALATION,
                    AiOperation.SPEECH_TRANSCRIPTION,
                    RecoveryReason.INPUT_SAFETY_REJECTED);
        }
        if (processed instanceof RoleplayInputProcessor.Invalid)
            return rejected(InputFailure.INVALID_INPUT);
        var canonical =
                ((RoleplayInputProcessor.Accepted) Objects.requireNonNull(processed))
                        .canonicalUtterance();
        var input =
                new RoleplayTurnInput(
                        context.scenario(),
                        context.state(),
                        new RoleplayTurnInput.LearnerTurn(
                                context.learnerTurn().turnId(), canonical),
                        context.recentDialogue());
        var steps = new StructuredRoleplayTurnSteps(prompts, llm, audio.traceContext(), input);
        var result =
                new RetryingRoleplayTurnExecutor()
                        .execute(input.learnerTurn().turnId(), steps, deadline);
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
                    attempt == 1
                            ? audio
                            : new SpeechTranscriptionRequest(
                                    audio.traceContext(), attempt, audio.audio(), audio.format());
            var result = deadline.withinBudget(() -> stt.transcribe(request));
            if (result instanceof AiCallResult.Success<SpeechTranscription>) return result;
            var failed = (AiCallResult.Failure<SpeechTranscription>) result;
            if (!failed.failure().retryable() || attempt == MAX_ATTEMPTS) return failed;
        }
    }

    private SynthesizedSpeech synthesize(
            AiTraceContext trace, String text, RoleplayTurnDeadline deadline) {
        for (int attempt = 1; ; attempt++) {
            var request = new SpeechSynthesisRequest(trace, attempt, text, null, null);
            var result = deadline.withinBudget(() -> tts.synthesize(request));
            if (result instanceof AiCallResult.Success<SynthesizedSpeech> success)
                return success.data();
            var failed = (AiCallResult.Failure<SynthesizedSpeech>) result;
            if (!failed.failure().retryable() || attempt == MAX_ATTEMPTS) return null;
        }
    }

    private static RoleplayTurnResult rejected(InputFailure failure) {
        return new RoleplayTurnResult.InputRejected(failure);
    }
}
