package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@Import(RoleplayHttpTestConfiguration.class)
@TestPropertySource(
        properties = {
            "roleplay.http.enabled=true",
            "roleplay.http.maximum-upload-bytes=16",
            "roleplay.http.maximum-request-bytes=65536",
            "roleplay.http.allowed-formats=WEBM",
            "roleplay.http.worker-count=2",
            "roleplay.http.queue-capacity=4",
            "roleplay.http.turn-budget=10s",
            "roleplay.progress.maximum-turn-count=1",
            "roleplay.progress.maximum-support-turns-per-micro-goal=1",
            "roleplay.retention.cleanup-enabled=false",
            "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long"
        })
class RoleplayMaximumTurnHttpIntegrationTest {
    private static final Path UPLOADS = createDirectory();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChildAccessCodeService codes;
    @Autowired RoleplayHttpTestConfiguration.State state;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("roleplay.http.spool-directory", () -> UPLOADS.toString());
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var paths = Files.walk(UPLOADS)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    @Test
    void voiceAtMaximumCompletesWithoutChangingGoalAndReplayMakesNoNewCalls() throws Exception {
        state.reset();
        state.useServerGoalEvaluator = true;
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var code =
                codes.issue(f.child(), UUID.randomUUID(), f.instructor()).response().accessCode();
        var session =
                (MockHttpSession)
                        mvc.perform(
                                        post("/api/v1/child-access-sessions")
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"accessCode\":\"" + code + "\"}"))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getRequest()
                                .getSession(false);
        var key = UUID.randomUUID();

        mvc.perform(
                        multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                                .file(
                                        new MockMultipartFile(
                                                "audio",
                                                "input.webm",
                                                "audio/webm",
                                                new byte[] {1, 2}))
                                .header("Idempotency-Key", key)
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.messages[0].text").value("함께해 줘서 고마워."));
        mvc.perform(get("/api/v1/activities/{id}/status", f.activity()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(
                        jdbc.queryForObject(
                                "select decision from roleplay_goal_assessment where session_id=?",
                                String.class,
                                f.session()))
                .isEqualTo("IN_PROGRESS");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=? and canonical_utterance is not null",
                                Integer.class,
                                f.session()))
                .isZero();
        var calls = List.copyOf(state.calls);

        mvc.perform(
                        multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                                .file(
                                        new MockMultipartFile(
                                                "audio",
                                                "input.webm",
                                                "audio/webm",
                                                new byte[] {1, 2}))
                                .header("Idempotency-Key", key)
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(state.calls).isEqualTo(calls);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity()))
                .isEqualTo(1);
    }

    @Test
    void providerFailureAtMaximumDoesNotCompleteOrAward() throws Exception {
        state.reset();
        state.useServerGoalEvaluator = true;
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var code =
                codes.issue(f.child(), UUID.randomUUID(), f.instructor()).response().accessCode();
        var session =
                (MockHttpSession)
                        mvc.perform(
                                        post("/api/v1/child-access-sessions")
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"accessCode\":\"" + code + "\"}"))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getRequest()
                                .getSession(false);
        for (int i = 0; i < 3; i++)
            state.failNextCall(AiOperation.CAUSE_ANALYSIS, AiFailureType.TIMEOUT);

        mvc.perform(
                        multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                                .file(
                                        new MockMultipartFile(
                                                "audio",
                                                "input.webm",
                                                "audio/webm",
                                                new byte[] {3}))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/activities/{id}/status", f.activity()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity()))
                .isZero();
        assertThat(state.calls).doesNotContain("RESPONSE_GENERATION", "TTS");
    }

    private static Path createDirectory() {
        try {
            return Files.createTempDirectory("neuringo-max-turn-http-");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
