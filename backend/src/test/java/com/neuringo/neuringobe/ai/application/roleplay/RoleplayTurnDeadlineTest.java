package com.neuringo.neuringobe.ai.application.roleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RoleplayTurnDeadlineTest {
    private static final UUID TURN = UUID.randomUUID();
    private static final UUID GOAL = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private final RoleplayTurnDeadline deadline =
            new RoleplayTurnDeadline(Duration.ofSeconds(60), clock::get);
    private final Steps steps = new Steps();

    @Test
    void budgetExpiresAtExactlySixtySecondsAndCancellationCannotRestoreIt() {
        clock.set(Duration.ofSeconds(59).toNanos());
        assertThat(deadline.remainingNanos()).isEqualTo(Duration.ofSeconds(1).toNanos());
        clock.set(Duration.ofSeconds(60).toNanos());
        assertThatThrownBy(deadline::requireActive)
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        deadline.cancel();
        clock.set(0);
        assertThat(deadline.remainingNanos()).isZero();
        assertThatThrownBy(() -> RoleplayTurnDeadline.start(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elapsedTimeWorksAcrossNanoTimeWraparound() {
        AtomicLong wrapping = new AtomicLong(Long.MAX_VALUE - 5);
        var shortDeadline = new RoleplayTurnDeadline(Duration.ofNanos(10), wrapping::get);
        wrapping.addAndGet(9);
        assertThat(shortDeadline.remainingNanos()).isEqualTo(1);
        wrapping.incrementAndGet();
        assertThat(shortDeadline.remainingNanos()).isZero();
    }

    @Test
    void expiredBudgetDoesNotCallAnyStage() {
        deadline.cancel();
        assertThat(new RetryingRoleplayTurnExecutor().execute(TURN, steps, deadline))
                .isInstanceOf(RoleplayTurnResult.TimedOut.class);
        assertThat(steps.calls).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = AiOperation.class,
            names = {"CAUSE_ANALYSIS", "RESPONSE_GENERATION", "RESPONSE_EVALUATION"})
    void lateStageSuccessCannotAdvanceOrBecomeReady(AiOperation lateStage) {
        steps.expireAt = lateStage;
        assertThat(new RetryingRoleplayTurnExecutor().execute(TURN, steps, deadline))
                .isInstanceOf(RoleplayTurnResult.TimedOut.class);
        assertThat(steps.calls.getLast()).isEqualTo(lateStage);
        assertThat(steps.calls)
                .hasSize(
                        lateStage == AiOperation.CAUSE_ANALYSIS
                                ? 1
                                : lateStage == AiOperation.RESPONSE_GENERATION ? 2 : 3);
    }

    @Test
    void evaluationRetriesUseRemainingBudgetInsteadOfAnotherSixtySeconds() {
        steps.advanceTime = true;
        steps.failEvaluationOnce = true;
        var result = new RetryingRoleplayTurnExecutor().execute(TURN, steps, deadline);
        assertThat(result).isInstanceOf(RoleplayTurnResult.TimedOut.class);
        assertThat(steps.calls)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        assertThat(clock.get()).isEqualTo(Duration.ofSeconds(60).toNanos());
    }

    @Test
    void earlierSpeechTimeAndLaterTtsTimeConsumeTheSameBudget() {
        try (var workers = Executors.newSingleThreadExecutor()) {
            clock.set(Duration.ofSeconds(20).toNanos()); // Already spent by STT.
            var result =
                    new RoleplayTurnRunner(workers)
                            .execute(
                                    deadline,
                                    shared -> {
                                        assertThat(shared).isSameAs(deadline);
                                        var approved =
                                                new RetryingRoleplayTurnExecutor()
                                                        .execute(TURN, steps, shared);
                                        assertThat(approved)
                                                .isInstanceOf(RoleplayTurnResult.Ready.class);
                                        return shared.withinBudget(
                                                () -> {
                                                    clock.addAndGet(
                                                            Duration.ofSeconds(40)
                                                                    .toNanos()); // TTS completes
                                                    // too late.
                                                    return approved;
                                                });
                                    });
            assertThat(result).isInstanceOf(RoleplayTurnResult.TimedOut.class);
        }
    }

    @Test
    void readyResultBeforeDeadlinePassesThrough() {
        try (var workers = Executors.newSingleThreadExecutor()) {
            assertThat(
                            new RoleplayTurnRunner(workers)
                                    .execute(
                                            deadline,
                                            shared ->
                                                    new RetryingRoleplayTurnExecutor()
                                                            .execute(TURN, steps, shared)))
                    .isInstanceOf(RoleplayTurnResult.Ready.class);
        }
    }

    @Test
    void waitingReturnsEvenWhenProviderIgnoresCancellationAndLateResultCannotGenerate() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    var workers = Executors.newSingleThreadExecutor();
                    CountDownLatch entered = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch interrupted = new CountDownLatch(1);
                    var realDeadline = RoleplayTurnDeadline.start(Duration.ofSeconds(1));
                    steps.beforeAnalysis =
                            () -> {
                                entered.countDown();
                                awaitIgnoringInterrupt(release, interrupted);
                            };
                    try {
                        var result =
                                new RoleplayTurnRunner(workers)
                                        .execute(
                                                realDeadline,
                                                shared ->
                                                        new RetryingRoleplayTurnExecutor()
                                                                .execute(TURN, steps, shared));
                        assertThat(entered.getCount()).isZero();
                        assertThat(result).isInstanceOf(RoleplayTurnResult.TimedOut.class);
                        assertThat(((RoleplayTurnResult.TimedOut) result).message())
                                .isEqualTo("다시 한 번만 말해줄래?");
                        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
                        release.countDown();
                        workers.submit(() -> {}).get(1, TimeUnit.SECONDS);
                        assertThat(steps.calls).containsExactly(AiOperation.CAUSE_ANALYSIS);
                    } finally {
                        release.countDown();
                        workers.shutdownNow();
                    }
                });
    }

    @Test
    void callerInterruptionCancelsWorkAndPreservesInterruptFlag() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancellationSeen = new AtomicBoolean();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        steps.beforeAnalysis =
                () -> {
                    entered.countDown();
                    awaitIgnoringInterrupt(release, new CountDownLatch(1));
                };
        Thread caller =
                Thread.ofVirtual()
                        .unstarted(
                                () -> {
                                    try {
                                        new RoleplayTurnRunner(workers)
                                                .execute(
                                                        deadline,
                                                        shared ->
                                                                new RetryingRoleplayTurnExecutor()
                                                                        .execute(
                                                                                TURN, steps,
                                                                                shared));
                                    } catch (CancellationException expected) {
                                        cancellationSeen.set(true);
                                        interruptPreserved.set(
                                                Thread.currentThread().isInterrupted());
                                    }
                                });
        try {
            caller.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(cancellationSeen.get()).isTrue();
            assertThat(interruptPreserved.get()).isTrue();
            release.countDown();
            workers.submit(() -> {}).get(1, TimeUnit.SECONDS);
            assertThat(steps.calls).containsExactly(AiOperation.CAUSE_ANALYSIS);
        } finally {
            release.countDown();
            caller.interrupt();
            workers.shutdownNow();
        }
    }

    @Test
    void programmingErrorsAreNotMisreportedAsTimeouts() {
        try (var workers = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(
                            () ->
                                    new RoleplayTurnRunner(workers)
                                            .execute(
                                                    deadline,
                                                    shared -> {
                                                        throw new IllegalStateException(
                                                                "synthetic defect");
                                                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("synthetic defect");
        }
    }

    @Test
    void queueWaitingConsumesBudgetAndExpiredQueuedWorkNeverStarts() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pipelineCalled = new AtomicBoolean();
        workers.submit(
                () -> {
                    occupied.countDown();
                    awaitIgnoringInterrupt(release, new CountDownLatch(1));
                });
        try {
            assertThat(occupied.await(2, TimeUnit.SECONDS)).isTrue();
            var result =
                    new RoleplayTurnRunner(workers)
                            .execute(
                                    RoleplayTurnDeadline.start(Duration.ofMillis(100)),
                                    shared -> {
                                        pipelineCalled.set(true);
                                        return new RoleplayTurnResult.TimedOut();
                                    });
            assertThat(result).isInstanceOf(RoleplayTurnResult.TimedOut.class);
            release.countDown();
            workers.submit(() -> {}).get(1, TimeUnit.SECONDS);
            assertThat(pipelineCalled.get()).isFalse();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch, CountDownLatch interrupted) {
        boolean done = false;
        while (!done) {
            try {
                latch.await();
                done = true;
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    private final class Steps implements RetryableRoleplayTurnSteps {
        private final List<AiOperation> calls = new ArrayList<>();
        private AiOperation expireAt;
        private boolean advanceTime;
        private boolean failEvaluationOnce;
        private Runnable beforeAnalysis = () -> {};

        private void record(AiOperation operation) {
            calls.add(operation);
            if (operation == expireAt) clock.set(Duration.ofSeconds(60).toNanos());
            if (advanceTime)
                clock.addAndGet(
                        Duration.ofSeconds(operation == AiOperation.RESPONSE_EVALUATION ? 20 : 10)
                                .toNanos());
        }

        @Override
        public AiCallResult<AnalysisResult> analyze(UUID turnId, RoleplayRetryContext retry) {
            record(AiOperation.CAUSE_ANALYSIS);
            beforeAnalysis.run();
            return success(
                    new AnalysisResult(
                            turnId,
                            "ANSWER",
                            "RELATED",
                            "PARTIAL",
                            new AnalysisResult.PrimaryGap("MISSING_EMOTION", null),
                            new AnalysisResult.NextStrategy("ASK", GOAL, null, "S1"),
                            0.9),
                    AiOperation.CAUSE_ANALYSIS);
        }

        @Override
        public AiCallResult<CandidateResponse> generate(
                UUID turnId,
                UUID candidateId,
                AnalysisResult analysis,
                RoleplayRetryContext retry) {
            record(AiOperation.RESPONSE_GENERATION);
            return success(
                    new CandidateResponse(
                            candidateId,
                            turnId,
                            "친구는 어떤 기분일까?",
                            "QUESTION",
                            "ASK",
                            GOAL,
                            "S1",
                            List.of()),
                    AiOperation.RESPONSE_GENERATION);
        }

        @Override
        public AiCallResult<EvaluationResult> evaluate(
                UUID turnId,
                AnalysisResult analysis,
                CandidateResponse candidate,
                RoleplayRetryContext retry) {
            record(AiOperation.RESPONSE_EVALUATION);
            if (failEvaluationOnce) {
                failEvaluationOnce = false;
                return new AiCallResult.Failure<>(
                        new AiFailure(AiFailureType.TIMEOUT, null),
                        metadata(AiOperation.RESPONSE_EVALUATION));
            }
            return success(
                    new EvaluationResult(
                            candidate.candidateId(),
                            true,
                            EvaluationDecision.PASS,
                            null,
                            0,
                            List.of(),
                            null),
                    AiOperation.RESPONSE_EVALUATION);
        }

        private <T> AiCallResult<T> success(T data, AiOperation operation) {
            return new AiCallResult.Success<>(data, metadata(operation));
        }

        private AiCallMetadata metadata(AiOperation operation) {
            return new AiCallMetadata(
                    UUID.randomUUID(),
                    operation,
                    "fake",
                    "fake-model",
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
}
