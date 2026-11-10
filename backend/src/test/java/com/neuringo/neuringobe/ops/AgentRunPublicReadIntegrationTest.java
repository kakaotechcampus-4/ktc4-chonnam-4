package com.neuringo.neuringobe.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 시연·외부 평가에서는 계정 없이 대시보드를 봐야 한다. public-read 를 켜면 로그인하지 않은 요청과 허용 목록 밖의 강사도 조회한다. 여는 것은 조회 GET 뿐이다.
 */
@LocalProfileIntegrationTest
@TestPropertySource(
        properties = {
            "neuringo.agent-runs.enabled=true",
            "neuringo.agent-runs.public-read=true",
            "neuringo.agent-runs.operators="
        })
class AgentRunPublicReadIntegrationTest {
    @Autowired private MockMvcTester mvc;
    @Autowired private TestFixtures fixtures;

    @Test
    void 로그인하지_않아도_요약과_목록을_읽는다() {
        var overview = mvc.get().uri("/api/v1/ops/agent-runs/overview?hours=24").exchange();
        assertThat(overview.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(TestFixtures.body(overview), "$.data.window.hours"))
                .isEqualTo(24);

        assertThat(mvc.get().uri("/api/v1/ops/agent-runs").exchange().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void 로그인하지_않아도_없는_실행_번호는_실행_기록이_없다고_답한다() {
        var detail = mvc.get().uri("/api/v1/ops/agent-runs/" + UUID.randomUUID()).exchange();
        assertThat(detail.getResponse().getStatus()).isEqualTo(404);
        assertThat(JsonPath.<String>read(TestFixtures.body(detail), "$.error.code"))
                .isEqualTo("AGENT_RUN_NOT_FOUND");
    }

    @Test
    void 허용_목록_밖의_강사도_읽는다() {
        assertThat(fixtures.get("/api/v1/ops/agent-runs/overview").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void 조회_밖의_요청은_열지_않는다() {
        assertThat(mvc.delete().uri("/api/v1/ops/agent-runs").exchange().getResponse().getStatus())
                .isIn(401, 403);
    }
}
