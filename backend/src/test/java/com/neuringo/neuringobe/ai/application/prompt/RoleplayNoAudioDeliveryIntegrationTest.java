package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.roleplay.application.RoleplaySpeechDelivery;
import com.neuringo.neuringobe.roleplay.infrastructure.speech.NoAudioRoleplaySpeechDelivery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

@IntegrationTest
@Import(RoleplayHttpTestConfiguration.class)
@TestPropertySource(
        properties = {
            "roleplay.http.enabled=true",
            "roleplay.speech.delivery=none",
            "test.roleplay.speech-delivery.enabled=false",
            "roleplay.text.enabled=true",
            "roleplay.text.maximum-characters=64",
            "roleplay.progress.maximum-turn-count=77",
            "roleplay.progress.maximum-support-turns-per-micro-goal=9",
            "roleplay.http.maximum-upload-bytes=16",
            "roleplay.http.maximum-request-bytes=65536",
            "roleplay.http.allowed-formats=WEBM",
            "roleplay.http.worker-count=1",
            "roleplay.http.queue-capacity=0",
            "roleplay.retention.cleanup-enabled=false",
            "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long"
        })
class RoleplayNoAudioDeliveryIntegrationTest {
    private static final Path SPOOL = spool();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("roleplay.http.spool-directory", SPOOL::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChildAccessCodeService codes;
    @Autowired RoleplayHttpTestConfiguration.State state;
    @Autowired RoleplaySpeechDelivery delivery;

    @BeforeEach
    void reset() {
        state.reset();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualDeliveryKeepsTextAndReplayDoesNotRepeatAiOrCommit(boolean voice) throws Exception {
        assertThat(delivery).isInstanceOf(NoAudioRoleplaySpeechDelivery.class);
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        submit(f.session(), session, key, voice)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.action").value("NONE"))
                .andExpect(jsonPath("$.data.messages[0].text").isNotEmpty())
                .andExpect(jsonPath("$.data.messages[0].speech.available").value(false))
                .andExpect(jsonPath("$.data.messages[0].speech.url").isEmpty())
                .andExpect(jsonPath("$.data.acceptedInput").value("친구가 울고 있어"))
                .andExpect(jsonPath("$.data.checkpointVersion").value(1))
                .andExpect(jsonPath("$.data.replayed").value(false));
        assertThat(state.calls).contains("TTS").doesNotContain("PUBLISH");
        assertThat(state.calls.contains("STT")).isEqualTo(voice);
        var calls = List.copyOf(state.calls);
        submit(f.session(), session, key, voice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.messages[0].speech.available").value(false))
                .andExpect(jsonPath("$.data.messages[0].speech.url").isEmpty())
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty())
                .andExpect(jsonPath("$.data.checkpointVersion").value(1))
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(state.calls).containsExactlyElementsOf(calls);
        assertThat(state.goalDecisions).hasValue(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_failure where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
    }

    private ResultActions submit(UUID id, MockHttpSession session, UUID key, boolean voice)
            throws Exception {
        AbstractMockHttpServletRequestBuilder<?> request =
                voice
                        ? multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", id)
                                .file(
                                        new MockMultipartFile(
                                                "audio",
                                                "synthetic.webm",
                                                "audio/webm",
                                                new byte[] {1, 2, 3}))
                        : post("/api/v1/roleplay-sessions/{id}/text-inputs", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"친구가 울고 있어\"}");
        return mvc.perform(request.header("Idempotency-Key", key).session(session).with(csrf()));
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
            return Files.createTempDirectory("neuringo-no-audio-");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
