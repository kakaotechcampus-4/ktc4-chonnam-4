package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.ops.service.AgentRunRecordingService;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 역할극 입력을 실제 HTTP 로 보냈을 때, 그 요청의 흐름(정상·다시 돎·안내로 끝남·오류)이 실행 기록으로 저장되고 운영 대시보드 API 로 읽히는지 확인한다. AI
 * 제공자만 가짜고 필터·작업자·재시도 루프·DB 는 실제다.
 *
 * <p>호출 로그(neuringo.ai.call-log)는 켜지 않는다. 실행 기록만 켜도 요청 번호가 붙고 기록이 남아야 한다.
 */
@IntegrationTest
@Import(RoleplayHttpTestConfiguration.class)
@TestPropertySource(
        properties = {
            "neuringo.agent-runs.enabled=true",
            "neuringo.agent-runs.operators=*",
            "roleplay.http.enabled=true",
            "roleplay.text.enabled=true",
            "roleplay.text.maximum-characters=64",
            "roleplay.progress.maximum-turn-count=77",
            "roleplay.progress.maximum-support-turns-per-micro-goal=9",
            "roleplay.http.maximum-upload-bytes=16",
            "roleplay.http.maximum-request-bytes=65536",
            "roleplay.http.allowed-formats=WEBM",
            "roleplay.http.worker-count=2",
            "roleplay.http.queue-capacity=4",
            // 흐름 종류를 단정하는 테스트라, 느린 PC·CI 에서 시간 초과 안내로 바뀌지 않게 넉넉히 준다.
            "roleplay.http.turn-budget=15s",
            "roleplay.retention.cleanup-enabled=false",
            "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long",
            "roleplay.http.notices[0].kind=SAFETY_ESCALATION_CHILD",
            "roleplay.http.notices[0].text=test child stop notice",
            "roleplay.http.notices[0].version=test-v1",
            "roleplay.http.notices[0].approval-reference=test-child-approval",
            "roleplay.http.notices[1].kind=SAFETY_ESCALATION_TEACHER",
            "roleplay.http.notices[1].text=private teacher stop notice",
            "roleplay.http.notices[1].version=test-v1",
            "roleplay.http.notices[1].approval-reference=test-teacher-approval",
            "roleplay.http.notices[2].kind=SAFETY_CHECK_ERROR",
            "roleplay.http.notices[2].text=test evaluation retry notice",
            "roleplay.http.notices[2].version=test-v1",
            "roleplay.http.notices[2].approval-reference=test-evaluation-approval"
        })
class RoleplayAgentRunHttpIntegrationTest {
    static final Path UPLOADS = directory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("roleplay.http.spool-directory", UPLOADS::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChildAccessCodeService codes;
    @Autowired RoleplayHttpTestConfiguration.State state;
    @Autowired AgentRunRecordingService recording;

    @BeforeEach
    void reset() {
        state.reset();
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var paths = Files.walk(UPLOADS)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    @Test
    void 한_번에_통과한_턴은_NORMAL_로_남고_글과_아동_세션_번호는_저장되지_않는다() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);

        String trace = recorded(text(f, session, "같이 놀자고 말할래"), "DELIVERED");

        var run = run(trace);
        assertThat(run)
                .containsEntry("workflow", "ROLEPLAY_TURN")
                .containsEntry("path", "NORMAL")
                .containsEntry("result", "DELIVERED")
                .containsEntry("input_type", "TEXT")
                .containsEntry("replayed", false);
        assertThat(run.get("ended_by")).isNull();
        assertThat(steps(trace))
                .extracting(step -> step.get("step"))
                .containsSubsequence(
                        "CAUSE_ANALYSIS",
                        "RESPONSE_GENERATION",
                        "RESPONSE_EVALUATION",
                        "TURN_RESOLUTION",
                        "RESULT");
        assertThat(steps(trace)).extracting(step -> step.get("status")).containsOnly("PASSED");
        assertThat(stored(trace))
                .doesNotContain(
                        "같이 놀자고 말할래",
                        "친구가 울고 있어",
                        "친구는 어떤 기분일까",
                        f.session().toString(),
                        f.child().toString(),
                        f.activity().toString());
    }

    @Test
    void 검수가_후보를_돌려보내면_RETRIED_로_남고_대시보드_API_가_그_흐름을_보여_준다() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.rejectNextCandidate("친구는 슬픈 기분일 거야. 친구는 어떤 기분일까?");

        String trace = recorded(text(f, session, "모르겠어"), "DELIVERED");

        assertThat(run(trace))
                .containsEntry("path", "RETRIED")
                .containsEntry("ended_by", "RESPONSE_EVALUATION")
                .containsEntry("reason", "REGENERATE");
        var rejected =
                steps(trace).stream()
                        .filter(step -> "REGENERATE".equals(step.get("verdict")))
                        .findFirst()
                        .orElseThrow();
        assertThat(rejected)
                .containsEntry("step", "RESPONSE_EVALUATION")
                .containsEntry("status", "REJECTED")
                .containsEntry("declared_next", "RESPONSE_GENERATION")
                .containsEntry("led_to", "RESPONSE_GENERATION");
        assertThat((String) rejected.get("codes")).contains("ANSWER_TOO_DIRECT");
        assertThat(stored(trace)).doesNotContain("친구는 슬픈 기분일 거야");

        var operator = instructor(f);
        mvc.perform(get("/api/v1/ops/agent-runs/{runId}", trace).with(authentication(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(trace))
                .andExpect(jsonPath("$.data.path").value("RETRIED"))
                .andExpect(jsonPath("$.data.steps[-1:].kind").value("RESULT"))
                .andExpect(
                        jsonPath("$.data.steps[?(@.verdict=='REGENERATE')].ledTo")
                                .value("RESPONSE_GENERATION"))
                .andExpect(jsonPath("$.data.spans").isNotEmpty())
                .andExpect(jsonPath("$.data.stageMs.LLM_CALL").exists());
        mvc.perform(
                        get("/api/v1/ops/agent-runs")
                                .param("hours", "1")
                                .param("workflow", "ROLEPLAY_TURN")
                                .param("path", "RETRIED")
                                .with(authentication(operator)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[?(@.runId=='" + trace + "')].reason").value("REGENERATE"))
                .andExpect(jsonPath("$.data[0].steps").isEmpty());
        // 성공 묶음(NORMAL·RETRIED)으로 읽으면 이 실행이 들어 있고, 실패 묶음에는 없다.
        mvc.perform(
                        get("/api/v1/ops/agent-runs")
                                .param("hours", "1")
                                .param("outcome", "SUCCESS")
                                .with(authentication(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].runId").value(org.hamcrest.Matchers.hasItem(trace)))
                .andExpect(
                        jsonPath("$.data[*].path")
                                .value(
                                        org.hamcrest.Matchers.everyItem(
                                                org.hamcrest.Matchers.is(
                                                        org.hamcrest.Matchers.oneOf(
                                                                "NORMAL", "RETRIED")))));
        mvc.perform(
                        get("/api/v1/ops/agent-runs")
                                .param("hours", "1")
                                .param("outcome", "FAILURE")
                                .with(authentication(operator)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].runId")
                                .value(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.hasItem(trace))));
        // 흐름 하나와 그 묶음이 맞지 않으면 조건 오류다.
        mvc.perform(
                        get("/api/v1/ops/agent-runs")
                                .param("outcome", "FAILURE")
                                .param("path", "RETRIED")
                                .with(authentication(operator)))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/ops/agent-runs/overview")
                                .param("hours", "1")
                                .with(authentication(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window.bucketMinutes").value(5))
                .andExpect(jsonPath("$.data.series.length()").value(12))
                .andExpect(
                        jsonPath("$.data.totals.retried")
                                .value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.workflows[0].workflow").value("ROLEPLAY_TURN"))
                .andExpect(
                        jsonPath(
                                        "$.data.transitions[?(@.from=='RESPONSE_EVALUATION'"
                                                + " && @.verdict=='REGENERATE'"
                                                + " && @.to=='RESPONSE_GENERATION')]")
                                .isNotEmpty())
                .andExpect(jsonPath("$.data.codes[?(@.code=='ANSWER_TOO_DIRECT')]").isNotEmpty())
                .andExpect(jsonPath("$.data.steps[?(@.step=='RESPONSE_GENERATION')]").isNotEmpty())
                .andExpect(
                        jsonPath("$.data.endings[?(@.endedBy=='RESPONSE_EVALUATION')]")
                                .isNotEmpty())
                .andExpect(jsonPath("$.data.stages[?(@.stage=='LLM_CALL')]").isNotEmpty())
                // 흐름 패턴: 검수 거절 뒤 다시 생성된 흐름이 성공 묶음에 따로 묶이고, 최근 실행 번호로 이 요청이 붙는다.
                .andExpect(
                        jsonPath(
                                        "$.data.flows[?(@.outcome=='SUCCESS' && @.path=='RETRIED')].recentRunIds[*]")
                                .value(org.hamcrest.Matchers.hasItem(trace)))
                .andExpect(
                        jsonPath(
                                        "$.data.flows[?(@.path=='RETRIED')].steps[?(@.verdict=='REGENERATE')].status")
                                .value(org.hamcrest.Matchers.hasItem("REJECTED")))
                .andExpect(
                        jsonPath("$.data.flows[?(@.path=='RETRIED')].steps[*].step")
                                .value(org.hamcrest.Matchers.hasItem("TURN_RESOLUTION")))
                // 자동 진단: 앞 기간(같은 길이)과 비교한 결과 모양. 실행이 적어 진단 항목은 없을 수 있다.
                .andExpect(jsonPath("$.data.diagnosis.previousFrom").exists())
                .andExpect(jsonPath("$.data.diagnosis.findings").isArray())
                .andExpect(jsonPath("$.data.endings[0].recentRunIds").isArray());
    }

    @Test
    void 안전_판정으로_멈춘_턴은_GUIDED_로_남는다() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.unsafe = true;

        String trace = recorded(text(f, session, "위험한 말"), "STOPPED");

        assertThat(run(trace))
                .containsEntry("path", "GUIDED")
                .containsEntry("result", "STOPPED")
                .containsEntry("reason", "INPUT_UNSAFE");
        assertThat(stored(trace)).doesNotContain("위험한 말", "stop notice");
    }

    @Test
    void 검수_호출이_한도까지_실패하면_FAILED_로_남고_어느_단계인지_알_수_있다() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        for (int i = 0; i < 3; i++)
            state.failNextCall(AiOperation.RESPONSE_EVALUATION, AiFailureType.TIMEOUT);

        String trace = recorded(text(f, session, "같이 놀자고 말할래"), "RETRY_REQUIRED");

        assertThat(run(trace))
                .containsEntry("path", "FAILED")
                .containsEntry("result", "RETRY_REQUIRED")
                .containsEntry("ended_by", "RESPONSE_EVALUATION")
                .containsEntry("reason", "LIMIT_REACHED");
        assertThat(
                        steps(trace).stream()
                                .filter(step -> "RESPONSE_EVALUATION".equals(step.get("step")))
                                .map(step -> step.get("status") + ":" + step.get("verdict")))
                .containsExactly("FAILED:TIMEOUT", "FAILED:TIMEOUT", "FAILED:TIMEOUT");
    }

    @Test
    void 음성_신뢰도가_낮아_다시_말해_달라고_한_턴은_GUIDED_이고_원인은_음성_인식이다() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.lowConfidence = true;

        String trace =
                recorded(
                        mvc.perform(
                                multipart(
                                                "/api/v1/roleplay-sessions/{id}/voice-inputs",
                                                f.session())
                                        .file(
                                                new MockMultipartFile(
                                                        "audio",
                                                        "input.webm",
                                                        "audio/webm",
                                                        new byte[] {1}))
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .session(session)
                                        .with(csrf())),
                        "REINPUT_REQUIRED");

        assertThat(run(trace))
                .containsEntry("path", "GUIDED")
                .containsEntry("input_type", "VOICE")
                .containsEntry("ended_by", "SPEECH_TRANSCRIPTION")
                .containsEntry("reason", "LOW_CONFIDENCE");
    }

    @Test
    void 로그인하지_않으면_대시보드_API_를_읽을_수_없다() throws Exception {
        mvc.perform(get("/api/v1/ops/agent-runs/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void 조회_조건이_틀리면_400_이다() throws Exception {
        var operator = instructor(RoleplayHttpFixtures.fixture(jdbc));
        mvc.perform(
                        get("/api/v1/ops/agent-runs/overview")
                                .param("hours", "0")
                                .with(authentication(operator)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_AGENT_RUN_FILTER"));
        mvc.perform(
                        get("/api/v1/ops/agent-runs")
                                .param("workflow", "NOT_A_WORKFLOW")
                                .with(authentication(operator)))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/ops/agent-runs/{runId}", UUID.randomUUID())
                                .with(authentication(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AGENT_RUN_NOT_FOUND"));
    }

    private ResultActions text(RoleplayHttpFixtures.Fixture f, MockHttpSession session, String text)
            throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        return mvc.perform(
                post("/api/v1/roleplay-sessions/{id}/text-inputs", f.session())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", text)))
                        .session(session)
                        .with(csrf()));
    }

    /** 응답 상태를 확인하고, 저장이 끝나기를 기다린 뒤 요청 번호(X-Trace-Id)를 돌려준다. */
    private String recorded(ResultActions response, String status) throws Exception {
        String trace =
                response.andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value(status))
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Trace-Id");
        assertThat(trace).isNotNull();
        assertThat(recording.drain(Duration.ofSeconds(10))).isTrue();
        return trace;
    }

    private Map<String, Object> run(String trace) {
        return jdbc.queryForMap("select * from agent_run where run_id = ?::uuid", trace);
    }

    private List<Map<String, Object>> steps(String trace) {
        return jdbc.queryForList(
                """
                select kind, step, attempt, status, verdict, codes::text as codes, declared_next, led_to
                from agent_run_step where run_id = ?::uuid order by seq
                """,
                trace);
    }

    /** 이 실행에 대해 저장된 모든 값을 글 하나로 모은다. 남으면 안 되는 값이 섞였는지 볼 때 쓴다. */
    private String stored(String trace) {
        return jdbc.queryForObject(
                """
                select row_to_json(r)::text || coalesce(
                    (select string_agg(row_to_json(s)::text, ' ') from agent_run_step s
                     where s.run_id = r.run_id), '')
                from agent_run r where r.run_id = ?::uuid
                """,
                String.class,
                trace);
    }

    private static UsernamePasswordAuthenticationToken instructor(RoleplayHttpFixtures.Fixture f) {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(f.instructor(), UUID.randomUUID(), UserRole.INSTRUCTOR),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")));
    }

    private MockHttpSession enter(RoleplayHttpFixtures.Fixture f) throws Exception {
        var code =
                codes.issue(f.child(), UUID.randomUUID(), f.instructor()).response().accessCode();
        return (MockHttpSession)
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accessCode\":\"" + code + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    private static Path directory() {
        try {
            return Files.createTempDirectory("neuringo-agent-run-http-");
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
