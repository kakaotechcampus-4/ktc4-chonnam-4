package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.infrastructure.input.ModelRoleplayInputProcessor;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.roleplay.config.RoleplayInputProcessorConfiguration;
import com.neuringo.neuringobe.roleplay.service.RoleplayLearnerPrivacyService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import({
    RoleplayHttpTestConfiguration.class,
    RoleplayRuntimeInputIntegrationTest.RuntimeInput.class
})
@TestPropertySource(
        properties = {
            "roleplay.http.enabled=true",
            "roleplay.text.enabled=true",
            "roleplay.text.maximum-characters=256",
            "roleplay.progress.maximum-turn-count=77",
            "roleplay.progress.maximum-support-turns-per-micro-goal=9",
            "roleplay.http.maximum-upload-bytes=16",
            "roleplay.http.maximum-request-bytes=65536",
            "roleplay.http.allowed-formats=WEBM",
            "roleplay.http.worker-count=2",
            "roleplay.http.queue-capacity=4",
            "roleplay.retention.cleanup-enabled=false",
            "roleplay.http.notices[0].kind=SAFETY_ESCALATION_CHILD",
            "roleplay.http.notices[0].text=합성 아동 안전 안내",
            "roleplay.http.notices[0].version=test-v1",
            "roleplay.http.notices[0].approval-reference=test-child",
            "roleplay.http.notices[1].kind=SAFETY_ESCALATION_TEACHER",
            "roleplay.http.notices[1].text=합성 강사 안전 안내",
            "roleplay.http.notices[1].version=test-v1",
            "roleplay.http.notices[1].approval-reference=test-teacher",
            "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long"
        })
class RoleplayRuntimeInputIntegrationTest {
    private static final Path SPOOL = spool();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("roleplay.http.spool-directory", SPOOL::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RuntimeInput {
        @Bean
        @Primary
        RoleplayInputProcessor runtimeInput(
                LlmProvider provider, JsonMapper mapper, RoleplayLearnerPrivacyService privacy) {
            return new RoleplayInputProcessorConfiguration()
                    .roleplayInputProcessor(
                            provider, mapper, privacy, false, java.util.Optional.empty());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChildAccessCodeService codes;
    @Autowired RoleplayHttpTestConfiguration.State state;
    @Autowired RoleplayInputProcessor processor;

    @BeforeEach
    void reset() {
        state.reset();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void actualProcessorMasksBothInputsBeforeAnalysisAndReplayDoesNotCallItAgain(boolean voice)
            throws Exception {
        assertThat(processor).isInstanceOf(ModelRoleplayInputProcessor.class);
        var f = RoleplayHttpFixtures.fixture(jdbc);
        jdbc.update("update child set display_name='김하늘' where child_id=?", f.child());
        state.transcriptionText = "김하늘은 안 속상해요 010-1234-5678";
        String canonical = "[이름]은 안 속상해요 [연락처]";
        var session = enter(f);
        UUID key = UUID.randomUUID();
        submit(f, session, key, voice, state.transcriptionText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.acceptedInput").value(canonical));
        assertThat(state.llmRequests).anyMatch(r -> r.operation() == AiOperation.INPUT_VALIDATION);
        assertThat(state.llmRequests)
                .allSatisfy(r -> assertThat(r.userPrompt()).doesNotContain("김하늘", "010-1234-5678"));
        assertThat(
                        jdbc.queryForObject(
                                "select canonical_utterance from roleplay_turn where session_id=?",
                                String.class,
                                f.session()))
                .isEqualTo(canonical);
        int calls = state.llmRequests.size();
        submit(f, session, key, voice, state.transcriptionText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty());
        assertThat(state.llmRequests).hasSize(calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSAFE", "REINPUT", "UNAVAILABLE", "BROKEN"})
    void rejectedOrFailedCheckNeverAdvancesLearningOrStoresRawInput(String decision)
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.inputJudgment =
                decision.equals("BROKEN")
                        ? "{}"
                        : "{\"decision\":\""
                                + decision
                                + "\",\"meaningPreserved\":true,\"privacyClear\":true,\"confidence\":0.9}";
        String expected =
                switch (decision) {
                    case "UNSAFE" -> "STOPPED";
                    case "REINPUT" -> "REINPUT_REQUIRED";
                    default -> "RETRY_REQUIRED";
                };
        submit(f, session, UUID.randomUUID(), false, "합성 검사 사례")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expected))
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty());
        assertThat(state.llmRequests).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_failure where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(state.calls)
                .doesNotContain(
                        "CAUSE_ANALYSIS", "RESPONSE_GENERATION", "RESPONSE_EVALUATION", "TTS");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isZero();
    }

    @Test
    void uncertainLocalPrivacyDoesNotCallExternalProvider() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        submit(f, enter(f), UUID.randomUUID(), false, "내 주소는 합성 주소야")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"));
        assertThat(state.llmRequests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSAFE", "UNAVAILABLE"})
    void voiceSafetyAndCheckerOutageDoNotIncrementFailureCounter(String decision) throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        state.transcriptionText = "합성 입력";
        state.inputJudgment =
                "{\"decision\":\""
                        + decision
                        + "\",\"meaningPreserved\":true,\"privacyClear\":true,\"confidence\":0.9}";
        submit(f, enter(f), UUID.randomUUID(), true, state.transcriptionText)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.status")
                                .value(decision.equals("UNSAFE") ? "STOPPED" : "RETRY_REQUIRED"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_failure where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(state.calls).doesNotContain("CAUSE_ANALYSIS", "TTS");
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            RoleplayHttpFixtures.Fixture f,
            MockHttpSession session,
            UUID key,
            boolean voice,
            String text)
            throws Exception {
        if (voice)
            return mvc.perform(
                    multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                            .file(
                                    new MockMultipartFile(
                                            "audio",
                                            "sample.webm",
                                            "audio/webm",
                                            new byte[] {1, 2, 3}))
                            .header("Idempotency-Key", key)
                            .session(session)
                            .with(csrf()));
        return mvc.perform(
                post("/api/v1/roleplay-sessions/{id}/text-inputs", f.session())
                        .header("Idempotency-Key", key)
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                JsonMapper.builder()
                                        .build()
                                        .writeValueAsString(java.util.Map.of("text", text))));
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

    private static Path spool() {
        try {
            return Files.createTempDirectory("runtime-input-test-");
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
