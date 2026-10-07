package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnRunner;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RoleplayCheckpointedTurnExecutorTest {
    @Test
    void recoveryNeverCallsCheckpointStore() {
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    executor(
                            workers,
                            (command, deadline) -> {
                                throw new AssertionError("Recovery must not persist");
                            });
            var result =
                    executor.execute(
                            RoleplayFixtures.trace(null, null),
                            0,
                            1,
                            RoleplayFixtures.REQUEST_ID,
                            "a".repeat(64),
                            RoleplayTurnDeadline.start(),
                            deadline ->
                                    new RoleplayTurnResult.InputRejected(
                                            RoleplayTurnResult.InputFailure.LOW_CONFIDENCE));
            assertThat(result).isInstanceOf(RoleplayCheckpointedTurnExecutor.Recovery.class);
        }
    }

    @Test
    void storageFailureCannotBecomeASuccessResponse() {
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    executor(
                            workers,
                            (command, deadline) -> {
                                throw new IllegalStateException("synthetic database failure");
                            });
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            RoleplayFixtures.trace(null, null),
                                            0,
                                            1,
                                            RoleplayFixtures.REQUEST_ID,
                                            "a".repeat(64),
                                            RoleplayTurnDeadline.start(),
                                            deadline -> spoken()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("synthetic database failure");
        }
    }

    @Test
    void expiredVoiceExecutionClosesUploadWithoutCallingProviderOrStore() {
        AtomicBoolean closed = new AtomicBoolean();
        RoleplayAudioResource resource =
                new RoleplayAudioResource() {
                    @Override
                    public SpeechTranscriptionRequest request() {
                        throw new AssertionError("Expired upload must not read");
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                };
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    executor(
                            workers,
                            (command, budget) -> {
                                throw new AssertionError("Expired input must not persist");
                            });
            var result =
                    executor.executeVoice(
                            resource,
                            RoleplayFixtures.input("test"),
                            null,
                            RoleplayFixtures.trace(null, null),
                            0,
                            1,
                            RoleplayFixtures.REQUEST_ID,
                            "a".repeat(64),
                            deadline);
            assertThat(result).isInstanceOf(RoleplayCheckpointedTurnExecutor.Recovery.class);
            assertThat(closed.get()).isTrue();
        }
    }

    private RoleplayCheckpointedTurnExecutor executor(
            java.util.concurrent.ExecutorService workers,
            java.util.function.BiFunction<
                            com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayCheckpointCommand,
                            RoleplayTurnDeadline,
                            RoleplayCheckpointStore.CommitResult>
                    commit) {
        var store =
                new RoleplayCheckpointStore() {
                    @Override
                    public CommitResult commit(
                            com.neuringo.neuringobe.ai.application.roleplay
                                            .RoleplayCheckpointCommand
                                    command,
                            RoleplayTurnDeadline deadline) {
                        return commit.apply(command, deadline);
                    }

                    @Override
                    public LookupResult findConfirmed(
                            com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity
                                    identity,
                            RoleplayTurnDeadline deadline) {
                        return new Absent();
                    }
                };
        return new RoleplayCheckpointedTurnExecutor(
                new RoleplayTurnRunner(workers),
                store,
                new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                (identity, deadline) -> java.util.Optional.of(() -> {}));
    }

    private RoleplayTurnResult.SpokenReady spoken() {
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
                        "synthetic response",
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
                "synthetic canonical",
                null);
    }
}
