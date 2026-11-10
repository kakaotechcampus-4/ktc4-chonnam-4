package com.neuringo.neuringobe.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder.Workflow;
import com.neuringo.neuringobe.ops.domain.AgentRun;
import com.neuringo.neuringobe.ops.repository.AgentRunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 실행 기록 저장은 쓰기 스레드가 한다. drain 은 넘긴 저장이 모두 끝날 때까지 기다려야 한다.
 *
 * <p>예전 drain 은 "일하는 스레드 수"와 "대기열"만 봤다. 쓰기 스레드가 대기열에서 작업을 꺼낸 직후 아직 일을 시작하지 않은 순간에는 둘 다 0 이라, 저장 전에
 * true 를 돌려줬다(develop fd05185 의 Backend CI 실패).
 */
class AgentRunRecordingServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    private final AtomicInteger inserted = new AtomicInteger();
    private final AgentRunStore store =
            new AgentRunStore(null, null) {
                @Override
                public void insert(AgentRun run) {
                    inserted.incrementAndGet();
                }
            };
    private final AgentRunRecordingService recording =
            new AgentRunRecordingService(store, transactions(), CLOCK);

    @AfterEach
    void shutdown() throws InterruptedException {
        recording.shutdown();
    }

    @Test
    void drain_은_넘긴_저장이_실제로_끝난_뒤에만_끝난다() {
        for (int i = 1; i <= 400; i++) {
            UUID runId = UUID.randomUUID();
            recording.begin(runId, Workflow.ROLEPLAY_TURN);
            recording.end(runId, Map.of("result", "DELIVERED", "http_status", 200));

            assertThat(recording.drain(Duration.ofSeconds(5))).isTrue();
            assertThat(inserted).as("%d번째 실행", i).hasValue(i);
        }
    }

    @Test
    void 저장이_실패해도_drain_은_기다림을_끝낸다() {
        var failing =
                new AgentRunRecordingService(
                        new AgentRunStore(null, null) {
                            @Override
                            public void insert(AgentRun run) {
                                throw new IllegalStateException("db down");
                            }
                        },
                        transactions(),
                        CLOCK);
        try {
            UUID runId = UUID.randomUUID();
            failing.begin(runId, Workflow.LEARNING_REPORT);
            failing.end(runId, Map.of("result", "COMPLETED"));

            assertThat(failing.drain(Duration.ofSeconds(5))).isTrue();
        } finally {
            try {
                failing.shutdown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static PlatformTransactionManager transactions() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
