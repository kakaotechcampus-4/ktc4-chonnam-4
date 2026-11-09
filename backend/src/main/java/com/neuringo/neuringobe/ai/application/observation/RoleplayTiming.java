package com.neuringo.neuringobe.ai.application.observation;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 요청 안의 기술 시간만 수집하며 worker에는 Submission으로 문맥을 전달한다. */
public final class RoleplayTiming {
    public enum Stage {
        HTTP_REQUEST,
        REQUEST_PREPARATION,
        UPLOAD_VALIDATION,
        WORKER_QUEUE,
        WORKER_EXECUTION,
        TURN_PREPARATION,
        REQUEST_CLAIM,
        CHECKPOINT_LOOKUP,
        CONTEXT_PREPARATION,
        INPUT_PROCESSING,
        STT,
        LLM_CALL,
        OUTPUT_VALIDATION,
        TTS,
        RESULT_COMMIT
    }

    private static final Logger log = LoggerFactory.getLogger(RoleplayTiming.class);
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final int MAX_SPANS = 256;

    private RoleplayTiming() {}

    public static Session begin(UUID requestId, AiDiagnostics diagnostics) {
        return begin(
                requestId,
                System::nanoTime,
                event -> {
                    if ("SUMMARY".equals(event.get("event")))
                        log.info("ROLEPLAY_TIMING request={} data={}", requestId, event);
                    else log.info("ROLEPLAY_STAGE request={} data={}", requestId, event);
                    diagnostics.record(requestId, "TIMING", event);
                });
    }

    static Session begin(UUID requestId, LongSupplier clock, Consumer<Map<String, Object>> sink) {
        var state = new State(requestId, clock, sink);
        var previous = CURRENT.get();
        CURRENT.set(new Frame(state, state.nodes.getFirst()));
        return new Session(state, previous);
    }

    public static <T> T measure(Stage stage, Supplier<T> action) {
        try (var scope = open(stage, null, 0)) {
            try {
                return action.get();
            } catch (RuntimeException | Error failure) {
                scope.outcome = "FAILED";
                throw failure;
            }
        }
    }

    public static <T> AiCallResult<T> ai(
            Stage stage, AiOperation operation, int attempt, Supplier<AiCallResult<T>> action) {
        try (var scope = open(stage, operation, attempt)) {
            try {
                var result = action.get();
                if (result instanceof AiCallResult.Failure<?> failed)
                    scope.outcome = "PROVIDER_FAILURE_" + failed.failure().type().name();
                return result;
            } catch (RuntimeException | Error failure) {
                scope.outcome = "FAILED";
                throw failure;
            }
        }
    }

    private static Scope open(Stage stage, AiOperation operation, int attempt) {
        var frame = CURRENT.get();
        if (frame == null) return new Scope(null, null, null);
        var node = frame.state.add(frame.node.id, stage, operation, attempt);
        if (node == null) return new Scope(null, null, null);
        CURRENT.set(new Frame(frame.state, node));
        return new Scope(frame.state, node, frame);
    }

    public static Submission submission() {
        var frame = CURRENT.get();
        return new Submission(
                frame,
                frame == null ? null : frame.state.add(frame.node.id, Stage.WORKER_QUEUE, null, 0));
    }

    public static final class Session implements AutoCloseable {
        private final State state;
        private final Frame previous;
        private boolean finished;

        private Session(State state, Frame previous) {
            this.state = state;
            this.previous = previous;
        }

        public void finish(boolean failed, Object result, Object replayed) {
            if (finished) return;
            finished = true;
            restore(previous);
            state.finish(failed, result, replayed);
        }

        @Override
        public void close() {
            finish(false, null, null);
        }
    }

    public static final class Submission {
        private final Frame parent;
        private final Node queue;
        private Node worker;
        private String cancellation;

        private Submission(Frame parent, Node queue) {
            this.parent = parent;
            this.queue = queue;
        }

        public synchronized Scope start() {
            if (queue == null) return new Scope(null, null, null);
            parent.state.end(queue, cancellation == null ? "COMPLETED" : cancellation);
            worker = parent.state.add(parent.node.id, Stage.WORKER_EXECUTION, null, 0);
            if (worker == null) return new Scope(null, null, null);
            if (cancellation != null) parent.state.cancel(worker, cancellation);
            var previous = CURRENT.get();
            CURRENT.set(new Frame(parent.state, worker));
            return new Scope(parent.state, worker, previous);
        }

        public synchronized void rejected() {
            cancel("REJECTED");
        }

        public synchronized void timedOut() {
            cancel("TIMED_OUT");
        }

        public synchronized void cancelled() {
            cancel("CANCELLED");
        }

        private void cancel(String reason) {
            cancellation = reason;
            if (queue == null) return;
            if (worker == null) parent.state.end(queue, reason + "_BEFORE_START");
            else parent.state.cancel(worker, reason);
        }
    }

    public static final class Scope implements AutoCloseable {
        private final State state;
        private final Node node;
        private final Frame previous;
        private String outcome = "COMPLETED";
        private boolean closed;

        private Scope(State state, Node node, Frame previous) {
            this.state = state;
            this.node = node;
            this.previous = previous;
        }

        public void failed() {
            outcome = "FAILED";
        }

        @Override
        public void close() {
            if (closed || state == null) return;
            closed = true;
            restore(previous);
            state.end(node, outcome);
        }
    }

    private static void restore(Frame frame) {
        if (frame == null) CURRENT.remove();
        else CURRENT.set(frame);
    }

    private record Frame(State state, Node node) {}

    private static final class Node {
        final int id, parent;
        final Stage stage;
        final AiOperation operation;
        final int attempt;
        final long start;
        long end = -1;
        String outcome, cancellation;

        Node(int id, int parent, Stage stage, AiOperation operation, int attempt, long start) {
            this.id = id;
            this.parent = parent;
            this.stage = stage;
            this.operation = operation;
            this.attempt = attempt;
            this.start = start;
        }
    }

    private static final class State {
        final UUID requestId;
        final LongSupplier clock;
        final Consumer<Map<String, Object>> sink;
        final long origin;
        final List<Node> nodes = new ArrayList<>();
        long closedAt = -1;
        boolean truncated;

        State(UUID requestId, LongSupplier clock, Consumer<Map<String, Object>> sink) {
            this.requestId = requestId;
            this.clock = clock;
            this.sink = sink;
            origin = clock.getAsLong();
            nodes.add(new Node(1, 0, Stage.HTTP_REQUEST, null, 0, 0));
        }

        long now() {
            try {
                return Math.max(0, clock.getAsLong() - origin);
            } catch (RuntimeException failure) {
                return 0;
            }
        }

        synchronized Node add(int parent, Stage stage, AiOperation operation, int attempt) {
            if (closedAt >= 0) return null;
            if (nodes.size() >= MAX_SPANS) {
                truncated = true;
                return null;
            }
            var node = new Node(nodes.size() + 1, parent, stage, operation, attempt, now());
            nodes.add(node);
            return node;
        }

        synchronized void cancel(Node node, String reason) {
            node.cancellation = reason;
        }

        synchronized void end(Node node, String outcome) {
            if (node.end >= 0) return;
            node.end = Math.max(node.start, now());
            node.outcome = node.cancellation == null ? outcome : node.cancellation;
            if (closedAt >= 0) {
                var event = fields(node, node.end);
                event.put("event", "STAGE_LATE");
                event.put("after_response", true);
                event.put(
                        "within_response_ms",
                        ms(Math.max(0, Math.min(closedAt, node.end) - node.start)));
                emit(event);
            }
        }

        synchronized void finish(boolean failed, Object result, Object replayed) {
            if (closedAt >= 0) return;
            closedAt = now();
            var root = nodes.getFirst();
            root.end = closedAt;
            root.outcome = failed ? "FAILED" : "COMPLETED";
            var inclusive = new LinkedHashMap<String, Long>();
            var own = new LinkedHashMap<String, Long>();
            var calls = new LinkedHashMap<String, Integer>();
            int inProgress = 0;
            for (var node : nodes) {
                long end = node.end < 0 ? closedAt : Math.min(closedAt, node.end);
                long duration = Math.max(0, end - node.start);
                long self = Math.max(0, duration - coveredChildren(node, end));
                var event = fields(node, end);
                event.put("self_ms", ms(self));
                emit(event);
                inclusive.merge(node.stage.name(), duration, Long::sum);
                own.merge(node.stage.name(), self, Long::sum);
                if (node.stage == Stage.LLM_CALL)
                    calls.merge(node.operation.name(), 1, Integer::sum);
                if (node.end < 0) inProgress++;
            }
            inclusive.replaceAll((key, value) -> ms(value));
            own.replaceAll((key, value) -> ms(value));
            emit(
                    AiDiagnostics.fields(
                            "event",
                            "SUMMARY",
                            "request_id",
                            requestId,
                            "duration_ms",
                            ms(closedAt),
                            "unclassified_ms",
                            own.get("HTTP_REQUEST"),
                            "inclusive_ms_by_stage",
                            inclusive,
                            "self_ms_by_stage",
                            own,
                            "llm_calls_by_operation",
                            calls,
                            "llm_calls",
                            calls.values().stream().mapToInt(Integer::intValue).sum(),
                            "repeated_llm_calls",
                            calls.values().stream().mapToInt(n -> Math.max(0, n - 1)).sum(),
                            "in_progress_at_response",
                            inProgress,
                            "truncated",
                            truncated,
                            "result",
                            result,
                            "replayed",
                            replayed));
        }

        private Map<String, Object> fields(Node node, long end) {
            return AiDiagnostics.fields(
                    "event",
                    "STAGE",
                    "request_id",
                    requestId,
                    "span_id",
                    node.id,
                    "parent_span_id",
                    node.parent,
                    "stage",
                    node.stage,
                    "operation",
                    node.operation,
                    "attempt",
                    node.attempt,
                    "start_ms",
                    ms(node.start),
                    "duration_ms",
                    ms(Math.max(0, end - node.start)),
                    "outcome",
                    node.end < 0 ? "IN_PROGRESS_AT_RESPONSE" : node.outcome);
        }

        private long coveredChildren(Node parent, long end) {
            var intervals =
                    nodes.stream()
                            .filter(n -> n.parent == parent.id)
                            .map(
                                    n ->
                                            new long[] {
                                                Math.max(parent.start, n.start),
                                                Math.min(end, n.end < 0 ? closedAt : n.end)
                                            })
                            .filter(i -> i[1] > i[0])
                            .sorted(Comparator.comparingLong(i -> i[0]))
                            .toList();
            long covered = 0, right = parent.start;
            for (var interval : intervals) {
                covered += Math.max(0, interval[1] - Math.max(right, interval[0]));
                right = Math.max(right, interval[1]);
            }
            return covered;
        }

        private void emit(Map<String, Object> event) {
            try {
                sink.accept(event);
            } catch (RuntimeException failure) {
                /* 관측 실패는 요청 처리로 전파하지 않는다. */
            }
        }

        private static long ms(long nanos) {
            return nanos / 1_000_000;
        }
    }
}
