package com.neuringo.neuringobe.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.neuringo.neuringobe.ops.config.AgentRunProperties;
import com.neuringo.neuringobe.ops.repository.AgentRunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 시연·외부 평가에서 쌓인 실행 기록은 지우지 않고 남긴다. 기간을 주면 그보다 오래된 기록만 지운다. */
class AgentRunRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final List<Instant> cutoffs = new ArrayList<>();
    private final AgentRunStore store =
            new AgentRunStore(null, null) {
                @Override
                public int deleteStartedBefore(Instant cutoff) {
                    cutoffs.add(cutoff);
                    return 0;
                }
            };

    @Test
    void 보관_기간이_0_이면_기록을_지우지_않는다() {
        purge(Duration.ZERO);

        assertThat(cutoffs).isEmpty();
    }

    @Test
    void 보관_기간을_주면_그보다_오래된_기록만_지운다() {
        purge(Duration.ofDays(14));

        assertThat(cutoffs).containsExactly(NOW.minus(Duration.ofDays(14)));
    }

    @Test
    void 설정이_없으면_지우지_않고_음수는_받지_않는다() {
        assertThat(properties(null).keepForever()).isTrue();
        assertThatThrownBy(() -> properties(Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void purge(Duration retention) {
        var recording =
                new AgentRunRecordingService(store, mock(PlatformTransactionManager.class), CLOCK);
        try {
            new AgentRunRetentionService(store, recording, properties(retention), CLOCK).purge();
        } finally {
            try {
                recording.shutdown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static AgentRunProperties properties(Duration retention) {
        return new AgentRunProperties(true, List.of(), retention, false);
    }
}
