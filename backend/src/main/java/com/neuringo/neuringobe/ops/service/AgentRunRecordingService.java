package com.neuringo.neuringobe.ops.service;

import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder;
import com.neuringo.neuringobe.ops.domain.AgentRunAssembler;
import com.neuringo.neuringobe.ops.repository.AgentRunStore;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실행 중의 사건을 메모리에 모았다가 실행이 끝나면 한 번에 저장한다.
 *
 * <p>저장은 전용 스레드 하나가 맡는다. 아동 응답을 보내는 스레드는 사건을 목록에 넣기만 하고 DB 를 기다리지 않는다. 대기열이 차거나 저장이 실패하면 그 실행 기록만
 * 버리고 경고 로그 한 줄을 남긴다. 워크플로우 결과에는 영향을 주지 않는다.
 *
 * <p>메모리에 드는 양은 동시에 열린 실행 수와 실행당 사건 수로 묶는다. 한도를 넘는 실행은 기록하지 않고, 한도를 넘는 사건은 버린 뒤 truncated 로 표시한다.
 */
@Service
@ConditionalOnProperty(name = "neuringo.agent-runs.enabled", havingValue = "true")
public class AgentRunRecordingService implements AgentRunRecorder {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecordingService.class);
    private static final int MAX_OPEN_RUNS = 256;
    private static final int MAX_EVENTS = 700;
    private static final int WRITE_QUEUE = 256;
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(15);

    private final Map<UUID, Open> open = new ConcurrentHashMap<>();
    private final AgentRunStore store;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ThreadPoolExecutor writer =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(WRITE_QUEUE),
                    task -> {
                        var thread = new Thread(task, "agent-run-writer");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public AgentRunRecordingService(
            AgentRunStore store, PlatformTransactionManager transactions, Clock clock) {
        this.store = store;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Override
    public void begin(UUID runId, Workflow workflow) {
        if (open.size() >= MAX_OPEN_RUNS) {
            log.warn("AGENT_RUN_SKIPPED workflow={} reason=TOO_MANY_OPEN_RUNS", workflow);
            return;
        }
        open.putIfAbsent(runId, new Open(workflow, clock.instant()));
    }

    @Override
    public void event(UUID runId, Map<String, Object> event) {
        var run = open.get(runId);
        if (run != null) run.add(event);
    }

    @Override
    public void end(UUID runId, Map<String, Object> result) {
        var run = open.remove(runId);
        if (run == null) return;
        try {
            writer.execute(() -> persist(runId, run, result));
        } catch (RejectedExecutionException full) {
            log.warn("AGENT_RUN_SKIPPED workflow={} reason=WRITE_QUEUE_FULL", run.workflow);
        }
    }

    private void persist(UUID runId, Open run, Map<String, Object> result) {
        try {
            var record =
                    AgentRunAssembler.assemble(
                            runId,
                            run.workflow,
                            run.startedAt,
                            run.snapshot(),
                            run.overflowed(),
                            result);
            transaction.executeWithoutResult(status -> store.insert(record));
        } catch (RuntimeException failure) {
            // 예외 메시지에는 SQL·값이 섞일 수 있어 종류만 남긴다.
            log.warn(
                    "AGENT_RUN_STORE_FAILED workflow={} type={}",
                    run.workflow,
                    failure.getClass().getSimpleName());
        }
    }

    /** 닫히지 않고 남은 실행을 치운다. 정상 경로에서는 필터와 AgentRunTrace 가 항상 닫으므로 남지 않는다. */
    public int discardAbandoned() {
        Instant cutoff = clock.instant().minus(ABANDONED_AFTER);
        int before = open.size();
        open.values().removeIf(run -> run.startedAt.isBefore(cutoff));
        return before - open.size();
    }

    /** 대기 중인 저장을 끝낸다. 테스트와 종료 절차에서 쓴다. */
    public boolean drain(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (writer.getActiveCount() > 0 || !writer.getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
        }
        return true;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static final class Open {
        private final Workflow workflow;
        private final Instant startedAt;
        private final List<Map<String, Object>> events = new ArrayList<>();
        private boolean overflowed;

        private Open(Workflow workflow, Instant startedAt) {
            this.workflow = workflow;
            this.startedAt = startedAt;
        }

        private synchronized void add(Map<String, Object> event) {
            if (events.size() >= MAX_EVENTS) overflowed = true;
            else events.add(event);
        }

        private synchronized List<Map<String, Object>> snapshot() {
            return List.copyOf(events);
        }

        private synchronized boolean overflowed() {
            return overflowed;
        }
    }
}
