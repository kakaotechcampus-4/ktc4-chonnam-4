package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplaySpeechTurnPipeline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResponder;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnRunner;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.infrastructure.input.TemporaryRoleplayAudioFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RoleplayResourceCheckpointTest {
    @TempDir Path directory;

    @Test
    void successfulReadPreservesProviderBytesAndCloseRemovesOwnedFile() throws Exception {
        Path file = upload();
        var resource =
                new TemporaryRoleplayAudioFile(
                        file, RoleplayFixtures.trace(null, null), AudioFormat.WEBM);
        try (resource) {
            assertThat(resource.request().audio()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        }
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void ownedUploadIsDeletedOnInputRejectionAndCannotBeReadAfterClosing() throws Exception {
        Path file = upload();
        var resource =
                new TemporaryRoleplayAudioFile(
                        file, RoleplayFixtures.trace(null, null), AudioFormat.WEBM);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var result =
                    responder(workers)
                            .executeVoice(
                                    resource,
                                    RoleplayFixtures.input("placeholder"),
                                    pipeline(false, null, null),
                                    RoleplayTurnDeadline.start());
            assertThat(result).isInstanceOf(RoleplayTurnOutcome.InputNoticeUnavailable.class);
        }
        assertThat(Files.exists(file)).isFalse();
        assertThatThrownBy(resource::request).isInstanceOf(IllegalStateException.class);
        resource.close();
    }

    @Test
    void providerExceptionStillDeletesUploadAndPreservesTheException() throws Exception {
        Path file = upload();
        try (var workers = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(
                            () ->
                                    responder(workers)
                                            .executeVoice(
                                                    new TemporaryRoleplayAudioFile(
                                                            file,
                                                            RoleplayFixtures.trace(null, null),
                                                            AudioFormat.WEBM),
                                                    RoleplayFixtures.input("placeholder"),
                                                    pipeline(true, null, null),
                                                    RoleplayTurnDeadline.start()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("synthetic provider defect");
        }
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void expiredBeforeWorkClosesResourceWithoutReadingAudio() throws Exception {
        Path file = upload();
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var result =
                    responder(workers)
                            .executeVoice(
                                    new TemporaryRoleplayAudioFile(
                                            file,
                                            RoleplayFixtures.trace(null, null),
                                            AudioFormat.WEBM),
                                    RoleplayFixtures.input("placeholder"),
                                    pipeline(true, null, null),
                                    deadline);
            assertThat(result).isInstanceOf(RoleplayTurnOutcome.Guidance.class);
        }
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void timeoutDeletesFileEvenIfProviderIgnoresInterruption() throws Exception {
        Path file = upload();
        var workers = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var result =
                    responder(workers)
                            .executeVoice(
                                    new TemporaryRoleplayAudioFile(
                                            file,
                                            RoleplayFixtures.trace(null, null),
                                            AudioFormat.WEBM),
                                    RoleplayFixtures.input("placeholder"),
                                    pipeline(false, entered, release),
                                    RoleplayTurnDeadline.start(Duration.ofSeconds(1)));
            assertThat(entered.getCount()).isZero();
            assertThat(result).isInstanceOf(RoleplayTurnOutcome.Guidance.class);
            assertThat(Files.exists(file)).isFalse();
            release.countDown();
            workers.submit(() -> {}).get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void oversizedAudioIsBoundedAndRemovedEvenWhenRequestCreationFails() throws Exception {
        Path file = directory.resolve("large.webm");
        Files.write(file, new byte[2 * 1024 * 1024 + 1]);
        try (var resource =
                new TemporaryRoleplayAudioFile(
                        file, RoleplayFixtures.trace(null, null), AudioFormat.WEBM)) {
            assertThatThrownBy(resource::request).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void cleanupFailureIsNotSilentlyReportedAsSuccess() {
        RoleplayAudioResource failing =
                new RoleplayAudioResource() {
                    @Override
                    public com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest
                            request() {
                        throw new AssertionError("Expired work cannot read");
                    }

                    @Override
                    public void close() {
                        throw new IllegalStateException("synthetic cleanup failure");
                    }
                };
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        try (var workers = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(
                            () ->
                                    responder(workers)
                                            .executeVoice(
                                                    failing,
                                                    RoleplayFixtures.input("placeholder"),
                                                    pipeline(false, null, null),
                                                    deadline))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("synthetic cleanup failure");
        }
    }

    @Test
    void checkpointCopiesOnlyApprovedCandidateAndCanonicalTextWithoutAudio() {
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        RoleplayFixtures.trace(null, null),
                        4,
                        2,
                        RoleplayFixtures.REQUEST_ID,
                        "a".repeat(64),
                        spoken(true));
        assertThat(command.candidateId()).isEqualTo(RoleplayFixtures.CANDIDATE_ID);
        assertThat(command.microGoalId()).isEqualTo(RoleplayFixtures.MG_EMOTION);
        assertThat(command.supportLevel()).isEqualTo("S1");
        assertThat(command.responseText()).isEqualTo("친구는 어떤 기분일까?");
        assertThat(command.canonicalUtterance()).isEqualTo("친구가 울고 있어");
        assertThat(command.expectedVersion()).isEqualTo(4);
        assertThat(command.turnNumber()).isEqualTo(2);
        assertThat(command.toString())
                .doesNotContain(
                        command.canonicalUtterance(),
                        command.responseText(),
                        command.turnId().toString());
    }

    @Test
    void textOnlySuccessCanPrepareTheSameCheckpoint() {
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        RoleplayFixtures.trace(null, null),
                        0,
                        1,
                        RoleplayFixtures.REQUEST_ID,
                        "a".repeat(64),
                        spoken(false));
        assertThat(command.candidateId()).isEqualTo(RoleplayFixtures.CANDIDATE_ID);
    }

    @Test
    void foreignTurnAndInvalidCheckpointCountersAreRejected() {
        var trace = RoleplayFixtures.trace(null, null);
        var other =
                new com.neuringo.neuringobe.ai.application.model.AiTraceContext(
                        trace.requestId(),
                        trace.activityId(),
                        trace.classId(),
                        trace.childId(),
                        trace.scenarioId(),
                        trace.scenarioVersion(),
                        trace.sessionId(),
                        java.util.UUID.randomUUID(),
                        null,
                        null);
        assertThatThrownBy(
                        () ->
                                RoleplayCheckpointCommand.fromApproved(
                                        other,
                                        0,
                                        1,
                                        RoleplayFixtures.REQUEST_ID,
                                        "a".repeat(64),
                                        spoken(false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RoleplayCheckpointCommand.fromApproved(
                                        trace,
                                        -1,
                                        1,
                                        RoleplayFixtures.REQUEST_ID,
                                        "a".repeat(64),
                                        spoken(false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RoleplayCheckpointCommand.fromApproved(
                                        trace,
                                        0,
                                        0,
                                        RoleplayFixtures.REQUEST_ID,
                                        "a".repeat(64),
                                        spoken(false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path upload() throws Exception {
        Path file = directory.resolve("upload.webm");
        Files.write(file, new byte[] {1, 2, 3});
        return file;
    }

    private RoleplayTurnResponder responder(java.util.concurrent.ExecutorService workers) {
        return new RoleplayTurnResponder(
                new RoleplayTurnRunner(workers),
                new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())));
    }

    private RoleplaySpeechTurnPipeline pipeline(
            boolean defect, CountDownLatch entered, CountDownLatch release) {
        return new RoleplaySpeechTurnPipeline(
                request -> {
                    if (defect) throw new IllegalStateException("synthetic provider defect");
                    if (entered != null) {
                        entered.countDown();
                        boolean done = false;
                        while (!done)
                            try {
                                release.await();
                                done = true;
                            } catch (InterruptedException ignored) {
                            }
                    }
                    return new AiCallResult.Success<>(
                            new SpeechTranscription("", 0.0, "fake", "fake", null, null),
                            new AiCallMetadata(
                                    request.traceContext().requestId(),
                                    AiOperation.SPEECH_TRANSCRIPTION,
                                    "fake",
                                    "fake",
                                    "test",
                                    "v1",
                                    null,
                                    0,
                                    1,
                                    null,
                                    null,
                                    "stop"));
                },
                request -> {
                    throw new AssertionError("Rejected input cannot call TTS");
                },
                text -> new RoleplayInputProcessor.Accepted("test"),
                new RoleplayPromptFactory(JsonMapper.builder().build(), null),
                new StructuredLlmExecutor(
                        request -> {
                            throw new AssertionError("Rejected input cannot call LLM");
                        }));
    }

    private RoleplayTurnResult.SpokenReady spoken(boolean audio) {
        var analysis =
                new AnalysisResult(
                        RoleplayFixtures.TURN_ID,
                        "ANSWER_ATTEMPT",
                        "PARTIALLY_RELEVANT",
                        "PARTIAL_UNDERSTANDING",
                        new AnalysisResult.PrimaryGap("MISSING_EMOTION", null),
                        new AnalysisResult.NextStrategy(
                                "PROBE_EMOTION", RoleplayFixtures.MG_EMOTION, null, "S1"),
                        0.9);
        var candidate =
                new CandidateResponse(
                        RoleplayFixtures.CANDIDATE_ID,
                        RoleplayFixtures.TURN_ID,
                        "친구는 어떤 기분일까?",
                        "GUIDING_QUESTION",
                        "PROBE_EMOTION",
                        RoleplayFixtures.MG_EMOTION,
                        "S1",
                        List.of());
        var evaluation =
                new EvaluationResult(
                        candidate.candidateId(),
                        true,
                        EvaluationDecision.PASS,
                        null,
                        0,
                        List.of(),
                        null);
        return new RoleplayTurnResult.SpokenReady(
                new RoleplayTurnResult.Ready(analysis, candidate, evaluation),
                "친구가 울고 있어",
                audio
                        ? new SynthesizedSpeech(
                                new byte[] {7}, AudioFormat.MP3, "fake", "fake", null)
                        : null);
    }
}
