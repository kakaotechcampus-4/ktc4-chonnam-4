package com.neuringo.neuringobe.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 기록을 지우지 않으므로 화면은 90일까지 고른다. 어느 기간을 골라도 시간대별 막대는 12~30개라 차트가 비거나 빽빽해지지 않는다. */
class AgentRunQueryPeriodTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 6, 24, 72, 168, 336, 720, 2160})
    void 화면이_고르는_기간은_모두_열려_있고_구간은_12_에서_30_개다(int hours) {
        assertThat(hours).isLessThanOrEqualTo(AgentRunQueryService.MAX_HOURS);

        int buckets = (int) Math.ceil(hours * 60.0 / AgentRunQueryService.bucketMinutes(hours));
        assertThat(buckets).isBetween(12, 30);
    }
}
