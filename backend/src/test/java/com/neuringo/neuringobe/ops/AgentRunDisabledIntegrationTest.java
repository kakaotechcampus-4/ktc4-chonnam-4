package com.neuringo.neuringobe.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** 기본값은 꺼짐이다. 끄면 기록기 빈이 없고 조회 주소도 없는 주소다. */
@LocalProfileIntegrationTest
class AgentRunDisabledIntegrationTest {
    @Autowired private TestFixtures fixtures;
    @Autowired private ObjectProvider<AgentRunRecorder> recorders;

    @Test
    void 꺼져_있으면_기록기도_조회_주소도_없다() {
        assertThat(recorders.getIfAvailable()).isNull();
        assertThat(fixtures.get("/api/v1/ops/agent-runs/overview").getResponse().getStatus())
                .isEqualTo(404);
    }
}
