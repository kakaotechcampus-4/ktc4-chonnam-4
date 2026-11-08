package com.neuringo.neuringobe.ai.application.observation;

import static com.neuringo.neuringobe.ai.application.observation.RoleplayTiming.Stage.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnRunner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RoleplayTimingTest {
    @Test
    void nestedTimesExcludeChildrenAndKeepProviderFailureAndRepeatedCalls() {
        var h = new Harness();
        try (var session = h.begin()) {
            h.advance(5);
            RoleplayTiming.measure(
                    INPUT_PROCESSING,
                    () -> {
                        h.advance(2);
                        RoleplayTiming.ai(
                                LLM_CALL,
                                AiOperation.INPUT_VALIDATION,
                                1,
                                () -> {
                                    h.advance(10);
                                    return success(AiOperation.INPUT_VALIDATION);
                                });
                        h.advance(3);
                        return true;
                    });
            RoleplayTiming.ai(
                    LLM_CALL,
                    AiOperation.RESPONSE_EVALUATION,
                    1,
                    () -> {
                        h.advance(4);
                        return new AiCallResult.Failure<String>(
                                new AiFailure(AiFailureType.EMPTY_OUTPUT, null),
                                metadata(AiOperation.RESPONSE_EVALUATION));
                    });
            RoleplayTiming.ai(
                    LLM_CALL,
                    AiOperation.RESPONSE_EVALUATION,
                    2,
                    () -> {
                        h.advance(6);
                        return success(AiOperation.RESPONSE_EVALUATION);
                    });
            RoleplayTiming.measure(
                    TTS,
                    () -> {
                        h.advance(7);
                        return true;
                    });
            h.advance(3);
            session.finish(false, "DELIVERED", false);
        }
        assertThat(h.stage(INPUT_PROCESSING).get("duration_ms")).isEqualTo(15L);
        assertThat(h.stage(INPUT_PROCESSING).get("self_ms")).isEqualTo(5L);
        assertThat(h.summary())
                .containsEntry("duration_ms", 40L)
                .containsEntry("unclassified_ms", 8L)
                .containsEntry("llm_calls", 3)
                .containsEntry("repeated_llm_calls", 1)
                .containsEntry("replayed", false);
        assertThat(h.events)
                .anySatisfy(
                        e ->
                                assertThat(e)
                                        .containsEntry("outcome", "PROVIDER_FAILURE_EMPTY_OUTPUT")
                                        .containsEntry("attempt", 1));
    }

    @Test
    void runnerPropagatesTimingToWorkerAndMeasuresQueueSeparately() {
        var h = new Harness();
        try (var workers =
                        new ThreadPoolExecutor(
                                1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
                            @Override
                            public void execute(Runnable task) {
                                h.advance(7);
                                super.execute(task);
                            }
                        };
                var session = h.begin()) {
            var result =
                    new RoleplayTurnRunner(workers)
                            .executeWithTimeout(
                                    RoleplayTurnDeadline.start(),
                                    deadline -> {
                                        RoleplayTiming.measure(
                                                CONTEXT_PREPARATION,
                                                () -> {
                                                    h.advance(3);
                                                    return true;
                                                });
                                        return RoleplayTiming.measure(
                                                TTS,
                                                () -> {
                                                    h.advance(4);
                                                    return "ok";
                                                });
                                    },
                                    () -> "timeout");
            assertThat(result).isEqualTo("ok");
            h.advance(2);
        }
        assertThat(h.stage(WORKER_QUEUE))
                .containsEntry("duration_ms", 7L)
                .containsEntry("outcome", "COMPLETED");
        assertThat(h.stage(WORKER_EXECUTION))
                .containsEntry("duration_ms", 7L)
                .containsEntry("self_ms", 0L);
        assertThat(h.stage(TTS).get("parent_span_id"))
                .isEqualTo(h.stage(WORKER_EXECUTION).get("span_id"));
        assertThat(h.summary())
                .containsEntry("duration_ms", 16L)
                .containsEntry("unclassified_ms", 2L);
    }

    @Test
    void overlappingChildrenUseIntervalUnionRatherThanSum() throws Exception {
        var h = new Harness();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor();
                var session = h.begin()) {
            var submission = RoleplayTiming.submission();
            h.advance(10);
            var future =
                    workers.submit(
                            () -> {
                                try (var scope = submission.start()) {
                                    started.countDown();
                                    await(release);
                                }
                            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            RoleplayTiming.measure(
                    OUTPUT_VALIDATION,
                    () -> {
                        h.advance(10);
                        return true;
                    });
            h.advance(10);
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
            h.advance(10);
        } finally {
            release.countDown();
        }
        assertThat(h.summary())
                .containsEntry("duration_ms", 40L)
                .containsEntry("unclassified_ms", 10L);
    }

    @Test
    void rejectedQueueNeverAppearsAsCompletedWork() {
        var h = new Harness();
        var workers = Executors.newSingleThreadExecutor();
        workers.shutdown();
        try (var session = h.begin()) {
            assertThatThrownBy(
                            () ->
                                    new RoleplayTurnRunner(workers)
                                            .executeWithTimeout(
                                                    RoleplayTurnDeadline.start(),
                                                    d -> "unexpected",
                                                    () -> "timeout"))
                    .isInstanceOf(RejectedExecutionException.class);
        }
        assertThat(h.stage(WORKER_QUEUE)).containsEntry("outcome", "REJECTED_BEFORE_START");
        assertThat(h.events).noneMatch(e -> e.get("stage") == WORKER_EXECUTION);
    }

    @Test
    void timeoutBeforeStartCancelsQueuedTaskWithoutRunningPipeline() {
        var h = new Harness();
        var deadline = RoleplayTurnDeadline.start();
        var future = new FutureTask<>(() -> "unexpected");
        var workers = mock(ExecutorService.class);
        when(workers.submit(any(Callable.class)))
                .thenAnswer(
                        call -> {
                            deadline.cancel();
                            return future;
                        });
        try (var session = h.begin()) {
            assertThat(
                            new RoleplayTurnRunner(workers)
                                    .executeWithTimeout(
                                            deadline,
                                            d -> {
                                                throw new AssertionError("실행 전 취소된 작업");
                                            },
                                            () -> "timeout"))
                    .isEqualTo("timeout");
        }
        assertThat(future.isCancelled()).isTrue();
        assertThat(h.stage(WORKER_QUEUE)).containsEntry("outcome", "TIMED_OUT_BEFORE_START");
        assertThat(h.events).noneMatch(e -> e.get("stage") == WORKER_EXECUTION);
    }

    @Test
    void callerInterruptionIsPreservedAndQueuedTimingIsCancelled() {
        var h = new Harness();
        var future = new FutureTask<>(() -> "unexpected");
        var workers = mock(ExecutorService.class);
        when(workers.submit(any(Callable.class))).thenReturn(future);
        try (var session = h.begin()) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(
                            () ->
                                    new RoleplayTurnRunner(workers)
                                            .executeWithTimeout(
                                                    RoleplayTurnDeadline.start(),
                                                    d -> "unexpected",
                                                    () -> "timeout"))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(h.stage(WORKER_QUEUE)).containsEntry("outcome", "CANCELLED_BEFORE_START");
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    void lateWorkIsReportedSeparatelyWithoutChangingResponseSummary() throws Exception {
        var h = new Harness();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor();
                var session = h.begin()) {
            var submission = RoleplayTiming.submission();
            var future =
                    workers.submit(
                            () -> {
                                try (var scope = submission.start()) {
                                    RoleplayTiming.measure(
                                            TTS,
                                            () -> {
                                                started.countDown();
                                                await(release);
                                                return true;
                                            });
                                }
                            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            h.advance(20);
            submission.timedOut();
            session.finish(false, "TIMED_OUT", false);
            assertThat(h.summary())
                    .containsEntry("duration_ms", 20L)
                    .containsEntry("in_progress_at_response", 2);
            h.advance(30);
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(h.stage(TTS))
                .containsEntry("outcome", "IN_PROGRESS_AT_RESPONSE")
                .containsEntry("duration_ms", 20L);
        assertThat(h.events)
                .anySatisfy(
                        e ->
                                assertThat(e)
                                        .containsEntry("event", "STAGE_LATE")
                                        .containsEntry("stage", TTS)
                                        .containsEntry("duration_ms", 50L)
                                        .containsEntry("within_response_ms", 20L));
        assertThat(h.events).filteredOn(e -> "SUMMARY".equals(e.get("event"))).hasSize(1);
        assertThat(h.summary()).containsEntry("duration_ms", 20L);
    }

    @Test
    void failuresAndDisabledTimingDoNotLeakTextOrThreadContext() {
        var h = new Harness();
        try (var session = h.begin()) {
            assertThatThrownBy(
                            () ->
                                    RoleplayTiming.measure(
                                            OUTPUT_VALIDATION,
                                            () -> {
                                                throw new IllegalArgumentException("비공개 출력 원문");
                                            }))
                    .hasMessage("비공개 출력 원문");
            session.finish(true, null, null);
        }
        assertThat(h.stage(OUTPUT_VALIDATION)).containsEntry("outcome", "FAILED");
        assertThat(h.events.toString()).doesNotContain("비공개 출력 원문");
        int count = h.events.size();
        assertThat(RoleplayTiming.measure(TTS, () -> "unchanged")).isEqualTo("unchanged");
        assertThat(h.events).hasSize(count);
    }

    @Test
    void recorderFailureCannotChangeResultAndSpanCountIsBounded() {
        var h = new Harness();
        try (var session =
                RoleplayTiming.begin(
                        UUID.randomUUID(),
                        h.clock::get,
                        e -> {
                            throw new IllegalStateException("비공개");
                        })) {
            assertThat(RoleplayTiming.measure(TTS, () -> "ok")).isEqualTo("ok");
        }
        try (var session = h.begin()) {
            for (int i = 0; i < 300; i++) RoleplayTiming.measure(TTS, () -> true);
            session.finish(false, "DELIVERED", true);
        }
        assertThat(h.summary())
                .containsEntry("truncated", true)
                .containsEntry("replayed", true)
                .containsEntry("llm_calls", 0);
        assertThat(h.events).hasSize(257);
    }

    @Test
    void concurrentRequestsAndReusedWorkerDoNotShareContext() throws Exception {
        var first = new Harness();
        var second = new Harness();
        var both = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a =
                    workers.submit(
                            () -> {
                                try (var session = first.begin()) {
                                    both.countDown();
                                    await(both);
                                    RoleplayTiming.measure(
                                            TTS,
                                            () -> {
                                                first.advance(7);
                                                return true;
                                            });
                                }
                            });
            var b =
                    workers.submit(
                            () -> {
                                try (var session = second.begin()) {
                                    both.countDown();
                                    await(both);
                                    RoleplayTiming.measure(
                                            RESULT_COMMIT,
                                            () -> {
                                                second.advance(12);
                                                return true;
                                            });
                                }
                            });
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            int count = first.events.size() + second.events.size();
            workers.submit(() -> RoleplayTiming.measure(STT, () -> "off")).get(5, TimeUnit.SECONDS);
            assertThat(first.events.size() + second.events.size()).isEqualTo(count);
        }
        assertThat(first.summary()).containsEntry("duration_ms", 7L);
        assertThat(second.summary()).containsEntry("duration_ms", 12L);
        assertThat(first.events).noneMatch(e -> e.get("stage") == RESULT_COMMIT);
        assertThat(second.events).noneMatch(e -> e.get("stage") == TTS);
        assertThat(first.summary().get("request_id"))
                .isNotEqualTo(second.summary().get("request_id"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("작업 해제 대기 초과");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static AiCallMetadata metadata(AiOperation operation) {
        return new AiCallMetadata(
                UUID.randomUUID(), operation, "fake", "fake", "v1", "v1", null, 1, 1, 1, 1, "STOP");
    }

    private static AiCallResult<String> success(AiOperation operation) {
        return new AiCallResult.Success<>("비공개 모델 출력", metadata(operation));
    }

    private static final class Harness {
        final AtomicLong clock = new AtomicLong();
        final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

        RoleplayTiming.Session begin() {
            return RoleplayTiming.begin(UUID.randomUUID(), clock::get, events::add);
        }

        void advance(long millis) {
            clock.addAndGet(millis * 1_000_000);
        }

        Map<String, Object> stage(RoleplayTiming.Stage stage) {
            return events.stream()
                    .filter(e -> "STAGE".equals(e.get("event")) && e.get("stage") == stage)
                    .findFirst()
                    .orElseThrow();
        }

        Map<String, Object> summary() {
            return events.stream()
                    .filter(e -> "SUMMARY".equals(e.get("event")))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
