package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.roleplay.service.RoleplayCanonicalRetentionService;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

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
            "roleplay.http.turn-budget=2s",
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
class RoleplayHttpIntegrationTest {
    static final Path UPLOADS = directory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("roleplay.http.spool-directory", UPLOADS::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChildAccessCodeService codes;
    @Autowired RoleplayHttpTestConfiguration.State state;
    @Autowired RoleplayCanonicalRetentionService retention;
    @Autowired ThreadPoolExecutor roleplayTurnWorkers;

    @BeforeEach
    void reset() {
        state.reset();
    }

    @AfterAll
    static void cleanup() throws Exception {
        Files.delete(UPLOADS);
    }

    static Path directory() {
        try {
            return Files.createTempDirectory("neuringo-roleplay-http-");
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @Test
    void normalUploadUsesRealChildSessionAndReturnsOnlyPublicCommittedData() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        var result =
                mvc.perform(
                                request(f.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                        .session(session)
                                        .with(csrf())
                                        .param("childId", UUID.randomUUID().toString())
                                        .param("fingerprint", "forged client fingerprint"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().exists("X-Trace-Id"))
                        .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                        .andExpect(jsonPath("$.data.acceptedInput").value("친구가 울고 있어"))
                        .andExpect(jsonPath("$.data.messages[0].speech.available").value(false))
                        .andExpect(jsonPath("$.data.checkpointVersion").value(1))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "synthetic raw",
                        "test-vendor",
                        "test-model",
                        "test-voice",
                        "assessmentEligibility",
                        "candidateId",
                        "approvalReference");
        assertThat(count(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select child_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.session()))
                .isEqualTo(f.child());
        assertEmptySpool();
    }

    @Test
    void evaluationFailureRetriesSameCandidateAndCommitsOnce() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.failNextCall(AiOperation.RESPONSE_EVALUATION, AiFailureType.TIMEOUT);

        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1, 2, 3}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.messages[0].text").value("친구는 어떤 기분일까?"))
                .andExpect(jsonPath("$.data.checkpointVersion").value(1));

        assertThat(state.llmRequests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        var evaluations =
                state.llmRequests.stream()
                        .filter(r -> r.operation() == AiOperation.RESPONSE_EVALUATION)
                        .toList();
        var candidateId = state.llmRequests.get(1).traceContext().candidateId();
        assertThat(candidateId).isNotNull();
        assertThat(evaluations)
                .extracting(r -> r.traceContext().candidateId())
                .containsExactly(candidateId, candidateId);
        assertThat(evaluations).extracting(LlmRequest::currentAttempt).containsExactly(1, 2);
        assertThat(evaluations)
                .extracting(LlmRequest::attemptContext)
                .containsExactly(new AiAttemptContext(1, 1, 1), new AiAttemptContext(1, 1, 2));
        assertThat(state.calls.stream().filter("TTS"::equals).count()).isEqualTo(1);
        assertThat(state.calls.stream().filter("PUBLISH"::equals).count()).isEqualTo(1);
        assertThat(count(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select candidate_id from roleplay_turn where session_id=?",
                                UUID.class,
                                f.session()))
                .isEqualTo(candidateId);
        assertEmptySpool();
    }

    @Test
    void rejectedCandidateIsReplacedAndOnlyApprovedCandidateIsDeliveredAndStored()
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        String rejectedText = "친구는 슬픈 기분일 거야. 친구는 어떤 기분일까?";
        state.rejectNextCandidate(rejectedText);
        var result =
                mvc.perform(
                                request(
                                                f.session(),
                                                UUID.randomUUID(),
                                                new byte[] {1, 2, 3},
                                                "audio/webm")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                        .andExpect(jsonPath("$.data.messages.length()").value(1))
                        .andExpect(jsonPath("$.data.messages[0].text").value("친구는 어떤 기분일까?"))
                        .andExpect(jsonPath("$.data.checkpointVersion").value(1))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(rejectedText);
        assertThat(state.llmRequests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION);
        var rejectedId = state.llmRequests.get(1).traceContext().candidateId();
        var approvedId = state.llmRequests.get(3).traceContext().candidateId();
        assertThat(approvedId).isNotNull().isNotEqualTo(rejectedId);
        assertThat(state.llmRequests.get(2).traceContext().candidateId()).isEqualTo(rejectedId);
        assertThat(state.llmRequests.get(4).traceContext().candidateId()).isEqualTo(approvedId);
        assertThat(state.llmRequests.get(3).userPrompt())
                .contains(rejectedId.toString(), rejectedText);
        assertThat(state.calls.stream().filter("TTS"::equals).count()).isEqualTo(1);
        assertThat(count(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select candidate_id from roleplay_turn where session_id=?",
                                UUID.class,
                                f.session()))
                .isEqualTo(approvedId);
        assertThat(
                        jdbc.queryForObject(
                                "select response_text from roleplay_turn where session_id=?",
                                String.class,
                                f.session()))
                .isEqualTo("친구는 어떤 기분일까?");
        assertEmptySpool();
    }

    @Test
    void exhaustedEvaluationRetriesReturnNoticeWithoutDeliveringOrSavingCandidate()
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        for (int i = 0; i < 3; i++) {
            state.failNextCall(AiOperation.RESPONSE_EVALUATION, AiFailureType.TIMEOUT);
        }
        var result =
                mvc.perform(
                                request(
                                                f.session(),
                                                UUID.randomUUID(),
                                                new byte[] {1, 2, 3},
                                                "audio/webm")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("RETRY_REQUIRED"))
                        .andExpect(jsonPath("$.data.action").value("RETRY_TURN"))
                        .andExpect(jsonPath("$.data.messages.length()").value(1))
                        .andExpect(
                                jsonPath("$.data.messages[0].text")
                                        .value("test evaluation retry notice"))
                        .andExpect(jsonPath("$.data.messages[0].speech.available").value(false))
                        .andExpect(jsonPath("$.data.acceptedInput").isEmpty())
                        .andExpect(jsonPath("$.data.checkpointVersion").isEmpty())
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("친구는 어떤 기분일까?", "candidateId", "test-evaluation-approval");
        assertThat(state.llmRequests)
                .extracting(LlmRequest::operation)
                .containsExactly(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION,
                        AiOperation.RESPONSE_EVALUATION);
        var evaluations =
                state.llmRequests.stream()
                        .filter(r -> r.operation() == AiOperation.RESPONSE_EVALUATION)
                        .toList();
        var candidateId = state.llmRequests.get(1).traceContext().candidateId();
        assertThat(candidateId).isNotNull();
        assertThat(evaluations)
                .extracting(r -> r.traceContext().candidateId())
                .containsExactly(candidateId, candidateId, candidateId);
        assertThat(evaluations).extracting(LlmRequest::currentAttempt).containsExactly(1, 2, 3);
        assertThat(state.calls).doesNotContain("TTS", "PUBLISH");
        assertThat(count(f)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_number from roleplay_session where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertEmptySpool();
    }

    @Test
    void completedReplayReturnsStoredTextWithoutCanonicalInputOrAdditionalCalls() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        mvc.perform(
                        request(f.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk());
        retention.completeNormallyAndDelete(f.session(), f.child(), f.activity());
        state.calls.clear();
        mvc.perform(
                        request(f.session(), key, new byte[] {1, 2, 3}, "video/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty())
                .andExpect(jsonPath("$.data.messages[0].text").value("친구는 어떤 기분일까?"));
        assertThat(state.calls).isEmpty();
        assertThat(count(f)).isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void changedBytesWithSameKeyReturnConflictErrorEnvelope() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        mvc.perform(
                        request(f.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk());
        state.calls.clear();
        mvc.perform(
                        request(f.session(), key, new byte[] {4}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_CONFLICT"));
        assertThat(state.calls).isEmpty();
        assertThat(count(f)).isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void missingAuthenticationCsrfAndInstructorAreRejected() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));
        var instructor =
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(
                                f.instructor(), UUID.randomUUID(), UserRole.INSTRUCTOR),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")));
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .with(authentication(instructor))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(state.calls).isEmpty();
        assertThat(count(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void foreignSessionAndPausedChildCannotSubmit() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var other = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        mvc.perform(
                        request(other.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isNotFound());
        jdbc.update("update child set status='PAUSED' where child_id=?", f.child());
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(state.calls).isEmpty();
        assertEmptySpool();
    }

    @Test
    void structuralUploadErrorsUseHttpStatusAndControlledErrorMessages() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[0], "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VOICE_UPLOAD_EMPTY"));
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[17], "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("VOICE_UPLOAD_TOO_LARGE"));
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "text/plain")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("VOICE_UPLOAD_FORMAT"));
        mvc.perform(
                        multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                                .session(session)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                                .file(
                                        new MockMultipartFile(
                                                "audio", "a.webm", "audio/webm", new byte[] {1}))
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(state.calls).isEmpty();
        assertThat(count(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void malformedIdsAndWrongRequestContentTypeAreRejected() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        mvc.perform(
                        multipart("/api/v1/roleplay-sessions/bad-id/voice-inputs")
                                .file(
                                        new MockMultipartFile(
                                                "audio", "a", "audio/webm", new byte[] {1}))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                                .file(
                                        new MockMultipartFile(
                                                "audio", "a", "audio/webm", new byte[] {1}))
                                .header("Idempotency-Key", "bad-key")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/roleplay-sessions/{id}/voice-inputs", f.session())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(state.calls).isEmpty();
        assertEmptySpool();
    }

    @Test
    void unavailableContextReturns503WithoutAiOrStorage() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.unavailable = true;
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_CONTEXT_UNAVAILABLE"));
        assertThat(state.calls).containsExactly("CONTEXT");
        assertThat(count(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void unsafeInputReturnsChildStopNoticeWithoutTeacherTextOrStoredEvent() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.unsafe = true;
        var result =
                mvc.perform(
                                request(
                                                f.session(),
                                                UUID.randomUUID(),
                                                new byte[] {1},
                                                "audio/webm")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("STOPPED"))
                        .andExpect(jsonPath("$.data.action").value("STOP_DIALOGUE"))
                        .andExpect(
                                jsonPath("$.data.messages[0].text").value("test child stop notice"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("private teacher", "synthetic raw", "approval-reference");
        assertThat(state.calls).containsExactly("CONTEXT", "STT", "INPUT");
        assertThat(count(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void lowConfidenceDoesNotProduceLearningTurnAndKeepsMissingNoticeExplicit() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.lowConfidence = true;
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOTICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.action").value("REQUEST_NEW_INPUT"));
        assertThat(state.calls).containsExactly("CONTEXT", "STT");
        assertThat(count(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void concurrentDuplicateReturns202AndStartsOnePipeline() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.blockStt = true;
        try (var clients = Executors.newSingleThreadExecutor()) {
            var first =
                    clients.submit(
                            () ->
                                    mvc.perform(
                                                    request(
                                                                    f.session(),
                                                                    key,
                                                                    new byte[] {1},
                                                                    "audio/webm")
                                                            .session(session)
                                                            .with(csrf()))
                                            .andReturn());
            try {
                assertThat(state.entered.await(1, TimeUnit.SECONDS)).isTrue();
                mvc.perform(
                                request(f.session(), key, new byte[] {1}, "audio/webm")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.status").value("PROCESSING"));
            } finally {
                state.release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(state.calls.stream().filter("STT"::equals).count()).isEqualTo(1);
            assertThat(count(f)).isEqualTo(1);
            assertEmptySpool();
        }
    }

    @Test
    void overallTimeoutDiscardsLateProviderResultAndRemovesFile() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.blockStt = true;
        try {
            mvc.perform(
                            request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                    .session(session)
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messages[0].text").value("다시 한 번만 말해줄래?"))
                    .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"));
            assertThat(count(f)).isZero();
            assertEmptySpool();
        } finally {
            state.release.countDown();
            assertThat(state.finished.await(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state.calls).doesNotContain("CAUSE_ANALYSIS", "TTS", "PUBLISH");
    }

    @Test
    void saturatedWorkerQueueReturns503AndCleansSpoolWithoutProviderCall() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var jobs = new java.util.ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 2; i++)
                jobs.add(
                        roleplayTurnWorkers.submit(
                                () -> {
                                    entered.countDown();
                                    await(release);
                                }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 4; i++) jobs.add(roleplayTurnWorkers.submit(() -> await(release)));
            mvc.perform(
                            request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                    .session(session)
                                    .with(csrf()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("ROLEPLAY_BUSY"));
            assertThat(state.calls).isEmpty();
            assertThat(count(f)).isZero();
            assertEmptySpool();
        } finally {
            release.countDown();
            for (var job : jobs) job.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void databaseFailureCannotReturnDeliveredAndSameKeyCanRetryAfterRollback() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        var constraint = "http_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table roleplay_turn add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + f.session()
                        + "'::uuid)");
        try {
            mvc.perform(
                            request(f.session(), key, new byte[] {1}, "audio/webm")
                                    .session(session)
                                    .with(csrf()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(count(f)).isZero();
            assertEmptySpool();
        } finally {
            jdbc.execute("alter table roleplay_turn drop constraint " + constraint);
        }
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(count(f)).isEqualTo(1);
        assertEmptySpool();
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity", "child", "scenario"})
    void stateChangedDuringAiCannotCommitANewTurn(String changed) throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.blockStt = true;
        try (var clients = Executors.newSingleThreadExecutor()) {
            var response =
                    clients.submit(
                            () ->
                                    mvc.perform(
                                                    request(
                                                                    f.session(),
                                                                    UUID.randomUUID(),
                                                                    new byte[] {1},
                                                                    "audio/webm")
                                                            .session(session)
                                                            .with(csrf()))
                                            .andReturn());
            try {
                assertThat(state.entered.await(1, TimeUnit.SECONDS)).isTrue();
                switch (changed) {
                    case "activity" ->
                            jdbc.update(
                                    "update activity set status='COMPLETED' where activity_id=?",
                                    f.activity());
                    case "child" ->
                            jdbc.update(
                                    "update child set status='PAUSED' where child_id=?", f.child());
                    case "scenario" ->
                            jdbc.update(
                                    "update activity set scenario_id=? where activity_id=?",
                                    UUID.randomUUID(),
                                    f.activity());
                    default -> throw new AssertionError("Unexpected test state");
                }
            } finally {
                state.release.countDown();
            }
            assertThat(response.get(3, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertThat(count(f)).isZero();
            assertEmptySpool();
            assertThat(
                            jdbc.queryForObject(
                                    "select row_version from roleplay_session where session_id=?",
                                    Long.class,
                                    f.session()))
                    .isZero();
            assertThat(state.calls).doesNotContain("PUBLISH");
        }
    }

    private MockMultipartHttpServletRequestBuilder request(
            UUID session, UUID key, byte[] bytes, String mime) {
        return (MockMultipartHttpServletRequestBuilder)
                multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", session)
                        .file(
                                new MockMultipartFile(
                                        "audio", "../../ignored-client-name.webm", mime, bytes))
                        .header("Idempotency-Key", key);
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

    private long count(RoleplayHttpFixtures.Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=?", Long.class, f.session());
    }

    private void assertEmptySpool() throws Exception {
        try (var files = Files.list(UPLOADS)) {
            assertThat(files.count()).isZero();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Test release timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
