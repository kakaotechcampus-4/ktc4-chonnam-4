package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplaySpeechTurnPipeline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResponder;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnRunner;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextAssembler;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplaySpeechTurnPipelineTest {
    private final List<String> calls = new ArrayList<>();
    private final List<SpeechTranscriptionRequest> sttRequests = new ArrayList<>();
    private final List<SpeechSynthesisRequest> ttsRequests = new ArrayList<>();
    private final List<LlmRequest> llmRequests = new ArrayList<>();
    private final SpeechTranscriptionRequest audio =
            new SpeechTranscriptionRequest(
                    RoleplayFixtures.trace(null, null), 1, new byte[] {1, 2, 3}, AudioFormat.WEBM);
    private final RoleplayTurnDeadline deadline = RoleplayTurnDeadline.start();
    private String raw = "synthetic-unfiltered-name";
    private Double confidence = 0.9;
    private int sttFailures;
    private int ttsFailures;
    private AiFailureType failureType = AiFailureType.TIMEOUT;
    private boolean unsafe;
    private boolean invalid;
    private boolean rejectCandidate;
    private String expireAt;

    @Test
    void voicePipelineReturnsCanonicalInputAndAudioForTheSameApprovedCandidate() {
        var result = (RoleplayTurnOutcome.SpokenResponse) execute();
        assertThat(calls)
                .containsExactly(
                        "STT",
                        "INPUT",
                        "CAUSE_ANALYSIS",
                        "RESPONSE_GENERATION",
                        "RESPONSE_EVALUATION",
                        "TTS");
        assertThat(result.canonicalUtterance()).isEqualTo("친구가 울고 있어");
        assertThat(result.speech().audio()).containsExactly((byte) 7, (byte) 8);
        assertThat(ttsRequests.getFirst().text()).isEqualTo(result.response().text());
        assertThat(ttsRequests.getFirst().traceContext().candidateId())
                .isEqualTo(result.response().candidateId());
        assertThat(result.assessmentEligibility())
                .isEqualTo(RoleplayTurnOutcome.AssessmentEligibility.ELIGIBLE);
        for (var request : llmRequests)
            assertThat(request.userPrompt()).doesNotContain(raw, "placeholder");
        assertThat(result.toString())
                .doesNotContain(raw, result.canonicalUtterance(), result.response().text());
    }

    @Test
    void assembledServerContextFlowsThroughSpeechAndAllLlmStages() {
        var trace = audio.traceContext();
        var fixture = RoleplayFixtures.input("unused fixture text");
        var scope =
                new Scope(
                        trace.childId(),
                        trace.classId(),
                        trace.activityId(),
                        UUID.randomUUID(),
                        trace.sessionId(),
                        trace.scenarioId(),
                        trace.scenarioVersion(),
                        3,
                        1,
                        fixture.state().currentMicroGoalId(),
                        fixture.state().currentSupportLevel(),
                        true);
        var prepared =
                new RoleplayContextAssembler(
                                key ->
                                        Optional.of(
                                                new RoleplayContextSource.Snapshot(
                                                        key,
                                                        "synthetic approval",
                                                        fixture.scenario(),
                                                        fixture.state(),
                                                        fixture.recentDialogue())))
                        .prepareVoice(scope, trace.requestId(), trace.turnId(), deadline);
        assertThat(prepared.trace()).isEqualTo(trace);
        var result = (RoleplayTurnOutcome.SpokenResponse) execute(prepared.input());
        assertThat(result.canonicalUtterance()).isEqualTo("친구가 울고 있어");
        assertThat(llmRequests).hasSize(3);
        for (var request : llmRequests) {
            assertThat(request.traceContext().scenarioId()).isEqualTo(scope.scenarioId());
            assertThat(request.traceContext().sessionId()).isEqualTo(scope.sessionId());
            assertThat(request.userPrompt()).doesNotContain(raw, "unused fixture text");
        }
        assertThat(ttsRequests.getFirst().traceContext().candidateId())
                .isEqualTo(result.response().candidateId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0", "0.39", "0.40", "null", "NaN"})
    void lowOrUnknownConfidenceSkipsInputProcessingLlmAndTts(String value) {
        confidence = value.equals("null") ? null : Double.valueOf(value);
        var result = (RoleplayTurnOutcome.Recovery) execute();
        assertThat(calls).containsExactly("STT");
        assertThat(result.action()).isEqualTo(RoleplayTurnOutcome.Action.REQUEST_NEW_INPUT);
        assertThat(result.retention()).isEqualTo(RoleplayTurnOutcome.Retention.DO_NOT_STORE);
        assertThat(result.assessmentEligibility())
                .isEqualTo(RoleplayTurnOutcome.AssessmentEligibility.NOT_ASSESSABLE);
    }

    @Test
    void silenceSkipsDownstreamRegardlessOfHighConfidence() {
        raw = " ";
        assertThat(execute()).isInstanceOf(RoleplayTurnOutcome.InputNoticeUnavailable.class);
        assertThat(calls).containsExactly("STT");
    }

    @Test
    void unsafeInputStopsBeforeAnyLlmOrTtsAndDoesNotReturnRawSpeech() {
        unsafe = true;
        var result = (RoleplayTurnOutcome.Recovery) execute();
        assertThat(calls).containsExactly("STT", "INPUT");
        assertThat(result.action()).isEqualTo(RoleplayTurnOutcome.Action.STOP_DIALOGUE);
        assertThat(result.retention()).isEqualTo(RoleplayTurnOutcome.Retention.DO_NOT_STORE);
        assertThat(result.toString()).doesNotContain(raw, audio.traceContext().turnId().toString());
    }

    @Test
    void invalidCanonicalInputNeverReachesLlm() {
        invalid = true;
        assertThat(execute()).isInstanceOf(RoleplayTurnOutcome.InputNoticeUnavailable.class);
        assertThat(calls).containsExactly("STT", "INPUT");
    }

    @Test
    void sttTechnicalRetriesPreserveSameAudioAndDoNotRepeatInputProcessing() {
        sttFailures = 2;
        assertThat(execute()).isInstanceOf(RoleplayTurnOutcome.SpokenResponse.class);
        assertThat(sttRequests.stream().map(SpeechTranscriptionRequest::currentAttempt))
                .containsExactly(1, 2, 3);
        for (var request : sttRequests) assertThat(request.audio()).containsExactly(audio.audio());
        assertThat(calls.stream().filter("INPUT"::equals).count()).isEqualTo(1);
    }

    @Test
    void sttRetryExhaustionRejectsWithoutCreatingCandidate() {
        sttFailures = 100;
        var result = (RoleplayTurnOutcome.Recovery) execute();
        assertThat(calls).containsExactly("STT", "STT", "STT");
        assertThat(result.retention()).isEqualTo(RoleplayTurnOutcome.Retention.DO_NOT_STORE);
        assertThat(ttsRequests).isEmpty();
    }

    @Test
    void nonRetryableSpeechFailureStopsAfterFirstCall() {
        sttFailures = 100;
        failureType = AiFailureType.AUTHENTICATION_ERROR;
        execute();
        assertThat(calls).containsExactly("STT");
    }

    @Test
    void ttsRetryExhaustionKeepsApprovedTextAndCanonicalInputWithoutAudio() {
        ttsFailures = 100;
        var result = (RoleplayTurnOutcome.SpokenResponse) execute();
        assertThat(result.response().text()).isEqualTo("친구는 어떤 기분일까?");
        assertThat(result.canonicalUtterance()).isEqualTo("친구가 울고 있어");
        assertThat(result.speech()).isNull();
        assertThat(ttsRequests).hasSize(3);
        assertThat(ttsRequests.stream().map(SpeechSynthesisRequest::text).distinct()).hasSize(1);
        assertThat(ttsRequests.stream().map(r -> r.traceContext().candidateId()).distinct())
                .hasSize(1);
        assertThat(llmRequests).hasSize(3);
    }

    @Test
    void candidateRejectedByEvaluatorNeverReachesTts() {
        rejectCandidate = true;
        assertThat(execute()).isInstanceOf(RoleplayTurnOutcome.Recovery.class);
        assertThat(ttsRequests).isEmpty();
        assertThat(calls.getLast()).isEqualTo("RESPONSE_EVALUATION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STT", "TTS"})
    void expiredSpeechResultCannotBecomeSuccessOrStartAnotherStage(String stage) {
        expireAt = stage;
        var result = (RoleplayTurnOutcome.Guidance) execute();
        assertThat(result.notice().kind()).isEqualTo(RoleplayNoticeCatalog.Kind.TIMEOUT);
        assertThat(calls.getLast()).isEqualTo(stage);
        assertThat(result.assessmentEligibility())
                .isEqualTo(RoleplayTurnOutcome.AssessmentEligibility.NOT_ASSESSABLE);
    }

    private RoleplayTurnOutcome execute() {
        return execute(RoleplayFixtures.input("placeholder"));
    }

    private RoleplayTurnOutcome execute(RoleplayTurnInput context) {
        var mapper = JsonMapper.builder().build();
        var pipeline =
                new RoleplaySpeechTurnPipeline(
                        request -> {
                            calls.add("STT");
                            sttRequests.add(request);
                            if ("STT".equals(expireAt)) deadline.cancel();
                            if (sttFailures-- > 0) return failure(AiOperation.SPEECH_TRANSCRIPTION);
                            return success(
                                    new SpeechTranscription(
                                            raw, confidence, "fake", "fake", null, null),
                                    AiOperation.SPEECH_TRANSCRIPTION);
                        },
                        request -> {
                            calls.add("TTS");
                            ttsRequests.add(request);
                            if ("TTS".equals(expireAt)) deadline.cancel();
                            if (ttsFailures-- > 0) return failure(AiOperation.SPEECH_SYNTHESIS);
                            return success(
                                    new SynthesizedSpeech(
                                            new byte[] {7, 8},
                                            AudioFormat.MP3,
                                            "fake",
                                            "fake",
                                            null),
                                    AiOperation.SPEECH_SYNTHESIS);
                        },
                        transcription -> {
                            calls.add("INPUT");
                            assertThat(transcription).isEqualTo(raw);
                            if (unsafe) return new RoleplayInputProcessor.Unsafe();
                            if (invalid) return new RoleplayInputProcessor.Invalid();
                            return new RoleplayInputProcessor.Accepted("친구가 울고 있어");
                        },
                        new RoleplayPromptFactory(mapper, null),
                        new StructuredLlmExecutor(
                                request -> {
                                    calls.add(request.operation().name());
                                    llmRequests.add(request);
                                    String content =
                                            switch (request.operation()) {
                                                case CAUSE_ANALYSIS ->
                                                        RoleplayFixtures.analysisJson(
                                                                "PROBE_EMOTION",
                                                                RoleplayFixtures.MG_EMOTION,
                                                                "S1");
                                                case RESPONSE_GENERATION ->
                                                        RoleplayFixtures.candidateJson(
                                                                        "친구는 어떤 기분일까?",
                                                                        "GUIDING_QUESTION")
                                                                .replace(
                                                                        RoleplayFixtures
                                                                                .CANDIDATE_ID
                                                                                .toString(),
                                                                        request.traceContext()
                                                                                .candidateId()
                                                                                .toString());
                                                case RESPONSE_EVALUATION ->
                                                        RoleplayFixtures.evaluationJson(
                                                                        rejectCandidate
                                                                                ? "CONFIRM_INPUT"
                                                                                : "PASS",
                                                                        rejectCandidate
                                                                                ? "INPUT_CONFIRMATION"
                                                                                : null,
                                                                        !rejectCandidate,
                                                                        0,
                                                                        rejectCandidate
                                                                                ? "[\"INPUT_MEANING_UNCLEAR\"]"
                                                                                : "[]",
                                                                        "null")
                                                                .replace(
                                                                        RoleplayFixtures
                                                                                .CANDIDATE_ID
                                                                                .toString(),
                                                                        request.traceContext()
                                                                                .candidateId()
                                                                                .toString());
                                                default ->
                                                        throw new AssertionError(
                                                                "Unexpected LLM operation");
                                            };
                                    return success(
                                            new LlmCompletion(
                                                    content, "fake", "fake", "stop", null, null),
                                            request.operation());
                                }));
        try (var workers = Executors.newSingleThreadExecutor()) {
            var responder =
                    new RoleplayTurnResponder(
                            new RoleplayTurnRunner(workers),
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())));
            return responder.execute(deadline, shared -> pipeline.execute(audio, context, shared));
        }
    }

    private <T> AiCallResult<T> failure(AiOperation operation) {
        return new AiCallResult.Failure<>(new AiFailure(failureType, null), metadata(operation));
    }

    private <T> AiCallResult<T> success(T value, AiOperation operation) {
        return new AiCallResult.Success<>(value, metadata(operation));
    }

    private AiCallMetadata metadata(AiOperation operation) {
        return new AiCallMetadata(
                audio.traceContext().requestId(),
                operation,
                "fake",
                "fake",
                "test/v1",
                "v1",
                null,
                0,
                1,
                null,
                null,
                "stop");
    }
}
