package com.neuringo.neuringobe.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 운영 대시보드는 강사 화면이 아니다. 허용 목록(neuringo.agent-runs.operators)에 있는 계정만 읽고, 그 밖의 강사에게는 화면이 있다는 사실도 알리지
 * 않는다(404).
 */
@LocalProfileIntegrationTest
@TestPropertySource(properties = "neuringo.agent-runs.enabled=true")
class AgentRunAccessIntegrationTest {
    private static final TestFixtures.Credentials OPERATOR = TestFixtures.newCredentials();

    @DynamicPropertySource
    static void operators(DynamicPropertyRegistry registry) {
        // 설정에는 대문자와 공백이 섞여 들어올 수 있다. 가입 이메일과 같은 규칙으로 맞춰 비교해야 한다.
        registry.add(
                "neuringo.agent-runs.operators",
                () -> "  " + OPERATOR.email().toUpperCase(Locale.ROOT) + " , someone@example.com");
    }

    @Autowired private TestFixtures fixtures;

    @Test
    void 허용_목록에_있는_계정은_요약과_목록을_읽는다() {
        UUID userId = fixtures.signUp(OPERATOR);
        var operator =
                new TestFixtures.Instructor(
                        userId,
                        OPERATOR.email(),
                        OPERATOR.password(),
                        fixtures.login(OPERATOR.email(), OPERATOR.password()));

        var overview = fixtures.get(operator, "/api/v1/ops/agent-runs/overview?hours=24");
        assertThat(overview.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(TestFixtures.body(overview), "$.data.window.hours"))
                .isEqualTo(24);
        assertThat(JsonPath.<Integer>read(TestFixtures.body(overview), "$.data.series.length()"))
                .isEqualTo(24);
        assertThat(fixtures.get(operator, "/api/v1/ops/agent-runs").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void 허용_목록에_없는_강사에게는_없는_주소처럼_답한다() {
        for (String uri :
                new String[] {
                    "/api/v1/ops/agent-runs/overview",
                    "/api/v1/ops/agent-runs",
                    "/api/v1/ops/agent-runs/" + UUID.randomUUID()
                }) {
            var response = fixtures.get(uri);
            assertThat(response.getResponse().getStatus()).as(uri).isEqualTo(404);
            assertThat(JsonPath.<String>read(TestFixtures.body(response), "$.error.code"))
                    .as(uri)
                    .isEqualTo("RESOURCE_NOT_FOUND");
        }
    }
}
