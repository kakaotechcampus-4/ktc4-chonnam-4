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
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog;
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
            "roleplay.text.enabled=true",
            "roleplay.text.maximum-characters=64",
            "roleplay.progress.maximum-turn-count=77",
            "roleplay.progress.maximum-support-turns-per-micro-goal=9",
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
        try (var paths = Files.walk(UPLOADS)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
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
                                        .param("achieved", "true")
                                        .param("isFinished", "true")
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
        assertThat(state.goalDecisions).hasValue(1);
        assertThat(recordCount(f)).isZero();
        mvc.perform(get("/api/v1/activities/{id}/status", f.activity()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        assertThat(
                        jdbc.queryForObject(
                                "select child_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.session()))
                .isEqualTo(f.child());
        assertEmptySpool();
    }

    @Test
    void serverObservationSnapshotReachesAnalysisAndEvaluationUnchanged() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var supplied =
                com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                        .inputForScenario(RoleplayFixtures.input("").scenario());
        var criterion =
                supplied.approvedCriteria().stream()
                        .filter(c -> c.microGoalId().equals(RoleplayFixtures.MG_EVENT))
                        .findFirst()
                        .orElseThrow();
        var prior =
                new com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput
                        .Observation(
                        new com.neuringo.neuringobe.ai.application.structured.output
                                .GoalObservationOutput.Reference(
                                UUID.randomUUID(), criterion.microGoalId(), criterion.reference()),
                        com.neuringo.neuringobe.ai.application.structured.output
                                .GoalObservationOutput.Status.IMPLICIT);
        state.contextObservations =
                com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                        .withSyntheticObservationContexts(
                                supplied.approvedCriteria(), List.of(prior));
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(state.llmRequests)
                .extracting(LlmRequest::responseSchemaVersion)
                .containsExactly(
                        "analysis-result/v2", "candidate-response/v1", "evaluation-result/v2");
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var expected = json.readTree(json.writeValueAsString(state.contextObservations));
        for (var call : List.of(state.llmRequests.get(0), state.llmRequests.get(2))) {
            var payload = json.readTree(call.userPrompt());
            assertThat(payload.path("goal_observation_context")).isEqualTo(expected);
            assertThat(payload.path("learner_turn").path("canonical_utterance").asString())
                    .isEqualTo("친구가 울고 있어");
            assertThat(call.userPrompt()).doesNotContain("synthetic raw private input");
        }
        var reviewPayload = json.readTree(state.llmRequests.get(2).userPrompt());
        assertThat(
                        reviewPayload
                                .path("analysis_result")
                                .path("goal_observation")
                                .path("analysis_id")
                                .asString())
                .isEqualTo(state.llmRequests.getFirst().traceContext().analysisId().toString());
        assertThat(state.goalDecisions).hasValue(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"ANALYSIS", "REVIEW", "UNSUPPORTED_REVIEW"})
    void missingOrUnapprovedObservationOutputNeverSynthesizesOrCommitsAndCanRetry(String missing)
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.omitObservationAnalysis = missing.equals("ANALYSIS");
        state.omitObservationReview = missing.equals("REVIEW");
        state.unsupportedObservationReview = missing.equals("UNSUPPORTED_REVIEW");
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").doesNotExist());
        assertThat(state.calls).doesNotContain("TTS");
        assertThat(state.goalDecisions).hasValue(0);
        assertThat(recordCount(f)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        state.omitObservationAnalysis = false;
        state.omitObservationReview = false;
        state.unsupportedObservationReview = false;
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void actualServerEvaluatorConnectsObservationHistoryCompletionAndReplay() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.useServerGoalEvaluator = true;
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.approvedContextFingerprint").doesNotExist())
                .andExpect(jsonPath("$.data.inputContext").doesNotExist());
        var firstTurn =
                jdbc.queryForObject(
                        "select turn_id from roleplay_turn where session_id=?",
                        UUID.class,
                        f.session());
        var key = UUID.randomUUID();
        state.activityGoalAchieved = true;
        mvc.perform(
                        request(f.session(), key, new byte[] {2}, "audio/webm")
                                .param("approvedContextFingerprint", "0".repeat(64))
                                .param("inputMicroGoalId", UUID.randomUUID().toString())
                                .param("inputSupportLevel", "S3")
                                .param("inputSupportTurn", "99")
                                .param("inputPromptCount", "99")
                                .param("inputAnswerRevealed", "true")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.approvedContextFingerprint").doesNotExist())
                .andExpect(jsonPath("$.data.inputContext").doesNotExist());
        var inputs =
                jdbc.queryForList(
                        "select input_micro_goal_id, input_support_level, input_support_turn, input_prompt_count, input_answer_revealed from roleplay_turn where session_id=? order by turn_number",
                        f.session());
        assertThat(inputs).hasSize(2);
        assertThat(inputs.getFirst())
                .containsEntry("input_micro_goal_id", RoleplayFixtures.MG_EMOTION)
                .containsEntry("input_support_level", "S1")
                .containsEntry("input_prompt_count", 0)
                .containsEntry("input_answer_revealed", false);
        assertThat(inputs.getLast())
                .containsEntry("input_micro_goal_id", RoleplayFixtures.MG_EMOTION)
                .containsEntry("input_support_level", "S1")
                .containsEntry("input_support_turn", 2)
                .containsEntry("input_prompt_count", 1)
                .containsEntry("input_answer_revealed", false);
        assertThat(state.llmRequests).hasSize(6);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var secondAnalysis = json.readTree(state.llmRequests.get(3).userPrompt());
        assertThat(secondAnalysis.path("goal_observation_context").path("confirmed_observations"))
                .hasSize(1);
        assertThat(
                        secondAnalysis
                                .path("goal_observation_context")
                                .path("confirmed_observations")
                                .get(0)
                                .path("reference")
                                .path("turn_id")
                                .asString())
                .isEqualTo(firstTurn.toString());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_goal_observation where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_goal_decision_basis where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(2);
        assertThat(recordCount(f)).isEqualTo(1);
        assertThat(canonicalCount(f)).isZero();
        mvc.perform(get("/api/v1/activities/{id}/status", f.activity()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(state.goalDecisions).hasValue(2);
        assertThat(state.goalCalledInTransaction).isFalse();
        state.goalDecisionUnavailable = true;
        state.unavailable = true;
        mvc.perform(
                        request(f.session(), key, new byte[] {2}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(state.llmRequests).hasSize(6);
        assertThat(state.goalDecisions).hasValue(2);
        assertThat(recordCount(f)).isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void contextChangedAfterAnalysisReturnsUnavailableWithoutSavingAndAllowsSameKeyRetry()
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.useServerGoalEvaluator = true;
        state.changeContextAfterSpeech = true;
        var key = UUID.randomUUID();
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_CONTEXT_UNAVAILABLE"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_goal_assessment where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(recordCount(f)).isZero();
        state.changeContextAfterSpeech = false;
        state.contextObservations = null;
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_goal_assessment where session_id=?",
                                Integer.class,
                                f.session()))
                .isEqualTo(1);
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
    void endedChildSessionCannotSubmitOrReplayVoiceInput() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var originalKey = UUID.randomUUID();
        mvc.perform(
                        request(f.session(), originalKey, new byte[] {1, 2, 3}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpointVersion").value(1));

        mvc.perform(delete("/api/v1/child-access-sessions/current").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        state.calls.clear();

        for (var key : List.of(originalKey, UUID.randomUUID())) {
            mvc.perform(
                            request(f.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                    .session(session)
                                    .with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        }

        assertThat(state.calls).isEmpty();
        assertThat(count(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void reentryWithOriginalCodeReplaysStoredTurnWithoutAdditionalCalls() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var code =
                codes.issue(f.child(), UUID.randomUUID(), f.instructor()).response().accessCode();
        var session = enter(code);
        var originalSessionId = session.getId();
        var key = UUID.randomUUID();
        mvc.perform(
                        request(f.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));
        var originalTurnId =
                jdbc.queryForObject(
                        "select turn_id from roleplay_turn where session_id=?",
                        UUID.class,
                        f.session());
        var originalText =
                jdbc.queryForObject(
                        "select response_text from roleplay_turn where session_id=?",
                        String.class,
                        f.session());

        mvc.perform(delete("/api/v1/child-access-sessions/current").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        var reentered = enter(code);
        assertThat(reentered.getId()).isNotEqualTo(originalSessionId);
        state.calls.clear();

        mvc.perform(
                        request(f.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                .session(reentered)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.messages[0].text").value(originalText))
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty())
                .andExpect(jsonPath("$.data.messages[0].speech.available").value(false))
                .andExpect(jsonPath("$.data.checkpointVersion").value(1));

        assertThat(state.calls).isEmpty();
        assertThat(count(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.session()))
                .isEqualTo(originalTurnId);
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void reentryContinuesWithNextTurnAndReplaysItsRetry() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var code =
                codes.issue(f.child(), UUID.randomUUID(), f.instructor()).response().accessCode();
        var session = enter(code);
        var firstKey = UUID.randomUUID();
        mvc.perform(
                        request(f.session(), firstKey, new byte[] {1, 2, 3}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpointVersion").value(1));
        var firstTurnId =
                jdbc.queryForObject(
                        "select last_turn_id from roleplay_session where session_id=?",
                        UUID.class,
                        f.session());

        mvc.perform(delete("/api/v1/child-access-sessions/current").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        var reentered = enter(code);
        var nextKey = UUID.randomUUID();
        state.calls.clear();

        mvc.perform(
                        request(f.session(), nextKey, new byte[] {4, 5, 6}, "audio/webm")
                                .session(reentered)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.checkpointVersion").value(2));
        assertThat(state.calls.stream().filter("STT"::equals).count()).isEqualTo(1);
        assertThat(state.calls.stream().filter("RESPONSE_GENERATION"::equals).count()).isEqualTo(1);
        assertThat(count(f)).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "select turn_number from roleplay_turn where session_id=? order by turn_number",
                                Integer.class,
                                f.session()))
                .containsExactly(1, 2);
        assertThat(
                        jdbc.queryForObject(
                                "select turn_id from roleplay_turn where session_id=? and idempotency_key=?",
                                UUID.class,
                                f.session(),
                                firstKey))
                .isEqualTo(firstTurnId);
        var nextTurnId =
                jdbc.queryForObject(
                        "select turn_id from roleplay_turn where session_id=? and idempotency_key=?",
                        UUID.class,
                        f.session(),
                        nextKey);
        assertThat(nextTurnId).isNotEqualTo(firstTurnId);
        state.calls.clear();

        mvc.perform(
                        request(f.session(), nextKey, new byte[] {4, 5, 6}, "audio/webm")
                                .session(reentered)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.checkpointVersion").value(2));
        assertThat(state.calls).isEmpty();
        assertThat(count(f)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_id from roleplay_session where session_id=?",
                                UUID.class,
                                f.session()))
                .isEqualTo(nextTurnId);
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                f.session()))
                .isEqualTo(2);
        assertEmptySpool();
    }

    @Test
    void reentryAsAnotherChildCannotReplayStoredTurn() throws Exception {
        var original = RoleplayHttpFixtures.fixture(jdbc);
        var other = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(original);
        var key = UUID.randomUUID();
        mvc.perform(
                        request(original.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));
        var originalTurnId =
                jdbc.queryForObject(
                        "select last_turn_id from roleplay_session where session_id=?",
                        UUID.class,
                        original.session());

        mvc.perform(delete("/api/v1/child-access-sessions/current").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        var reentered = enter(other);
        state.calls.clear();

        mvc.perform(
                        request(original.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                .session(reentered)
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(state.calls).isEmpty();
        assertThat(count(original)).isEqualTo(1);
        assertThat(count(other)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select last_turn_id from roleplay_session where session_id=?",
                                UUID.class,
                                original.session()))
                .isEqualTo(originalTurnId);
        assertThat(
                        jdbc.queryForObject(
                                "select row_version from roleplay_session where session_id=?",
                                Long.class,
                                original.session()))
                .isEqualTo(1);
        assertEmptySpool();

        mvc.perform(
                        request(other.session(), key, new byte[] {1, 2, 3}, "audio/webm")
                                .session(reentered)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.checkpointVersion").value(1));
        assertThat(count(other)).isEqualTo(1);
        assertThat(count(original)).isEqualTo(1);
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"));
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[17], "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"));
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "text/plain")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"));
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
        assertThat(state.goalDecisions).hasValue(0);
        assertThat(recordCount(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void lowConfidenceDoesNotProduceLearningTurnAndAlwaysProvidesReinputGuidance()
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.lowConfidence = true;
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"))
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
        state.activityGoalAchieved = true;
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
            assertThat(state.goalDecisions).hasValue(1);
            assertThat(recordCount(f)).isEqualTo(1);
            assertThat(canonicalCount(f)).isZero();
            assertEmptySpool();
        }
    }

    @Test
    void concurrentDifferentInputReturns409WithoutReplacingOriginalProcessingRequest()
            throws Exception {
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
                                request(f.session(), key, new byte[] {2}, "audio/webm")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("ROLEPLAY_CONFLICT"));
                mvc.perform(
                                request(f.session(), key, new byte[] {1}, "audio/webm")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.status").value("PROCESSING"));
                assertThat(state.calls).containsExactly("CONTEXT", "STT");
                assertThat(count(f)).isZero();
            } finally {
                state.release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            mvc.perform(
                            request(f.session(), key, new byte[] {1}, "audio/webm")
                                    .session(session)
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.replayed").value(true))
                    .andExpect(jsonPath("$.data.checkpointVersion").value(1));
            mvc.perform(
                            request(f.session(), key, new byte[] {2}, "audio/webm")
                                    .session(session)
                                    .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ROLEPLAY_CONFLICT"));
            assertThat(state.calls)
                    .containsExactly(
                            "CONTEXT",
                            "STT",
                            "INPUT",
                            "CAUSE_ANALYSIS",
                            "RESPONSE_GENERATION",
                            "RESPONSE_EVALUATION",
                            "TTS",
                            "PUBLISH");
            assertThat(count(f)).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select row_version from roleplay_session where session_id=?",
                                    Long.class,
                                    f.session()))
                    .isEqualTo(1L);
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

    @Test
    void achievedGoalCompletesThroughVoiceHttpAndReplaySkipsGoalEvaluation() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.activityGoalAchieved = true;

        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        mvc.perform(get("/api/v1/activities/{id}/status", f.activity()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(state.goalDecisions).hasValue(1);
        assertThat(state.goalCalledInTransaction).isFalse();
        assertThat(recordCount(f)).isEqualTo(1);
        assertThat(canonicalCount(f)).isZero();
        var original = completionSnapshot(f);
        var providerCalls = List.copyOf(state.calls);
        state.goalDecisionUnavailable = true;

        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(state.goalDecisions).hasValue(1);
        assertThat(state.calls).isEqualTo(providerCalls);
        assertThat(completionSnapshot(f)).isEqualTo(original);
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {2}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isConflict());
        assertThat(state.goalDecisions).hasValue(1);
        assertThat(count(f)).isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void unavailableGoalDecisionReturns503WithoutSavingOrDeliveringCandidate() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.goalDecisionUnavailable = true;
        var failed =
                mvc.perform(
                                request(f.session(), key, new byte[] {1}, "audio/webm")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.error.code").value("ROLEPLAY_CONTEXT_UNAVAILABLE"))
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andReturn();
        assertThat(failed.getResponse().getContentAsString())
                .doesNotContain("친구가 울고 있어", "친구는 어떤 기분일까?");
        assertThat(count(f)).isZero();
        assertThat(recordCount(f)).isZero();
        assertThat(state.calls).doesNotContain("PUBLISH");
        assertEmptySpool();

        state.goalDecisionUnavailable = false;
        state.activityGoalAchieved = true;
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(state.goalDecisions).hasValue(2);
        assertThat(count(f)).isEqualTo(1);
        assertThat(recordCount(f)).isEqualTo(1);
        assertThat(canonicalCount(f)).isZero();
    }

    @Test
    void actualRecordFailureThroughVoiceHttpRollsBackAndSameKeyCanRetry() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.activityGoalAchieved = true;
        var constraint = "http_record_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "alter table learning_record add constraint "
                        + constraint
                        + " check (activity_id <> '"
                        + f.activity()
                        + "'::uuid)");
        try {
            mvc.perform(
                            request(f.session(), key, new byte[] {1}, "audio/webm")
                                    .session(session)
                                    .with(csrf()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.data").doesNotExist());
            assertThat(count(f)).isZero();
            assertThat(recordCount(f)).isZero();
            mvc.perform(get("/api/v1/activities/{id}/status", f.activity()).session(session))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
            assertEmptySpool();
        } finally {
            jdbc.execute("alter table learning_record drop constraint " + constraint);
        }
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(recordCount(f)).isEqualTo(1);
        assertThat(count(f)).isEqualTo(1);
        assertThat(canonicalCount(f)).isZero();
    }

    @Test
    void recordedFailureReplaysWithoutContextSttOrLlmEvenWhenContextIsUnavailable()
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.lowConfidence = true;
        submitFailure(f, session, key, new byte[] {1}, "REQUEST_NEW_INPUT", false);
        var originalCalls = List.copyOf(state.calls);
        state.unavailable = true;
        submitFailure(f, session, key, new byte[] {1}, "REQUEST_NEW_INPUT", true);
        submitFailure(f, session, key, new byte[] {1}, "REQUEST_NEW_INPUT", true);
        assertThat(state.calls).containsExactlyElementsOf(originalCalls);
        assertThat(voiceFailureCount(f)).isEqualTo(1);
        assertThat(count(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void changedBytesUnderFailedKeyConflictBeforeStt() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.lowConfidence = true;
        submitFailure(f, session, key, new byte[] {1}, "REQUEST_NEW_INPUT", false);
        var originalCalls = List.copyOf(state.calls);
        mvc.perform(
                        request(f.session(), key, new byte[] {2}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_CONFLICT"));
        assertThat(state.calls).containsExactlyElementsOf(originalCalls);
        assertThat(voiceFailureCount(f)).isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void replayOfOldFailureUsesCurrentRecommendationWithoutRecounting() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var first = UUID.randomUUID();
        state.lowConfidence = true;
        submitFailure(f, session, first, new byte[] {1}, "REQUEST_NEW_INPUT", false);
        submitFailure(f, session, UUID.randomUUID(), new byte[] {2}, "REQUEST_NEW_INPUT", false);
        submitFailure(
                f, session, UUID.randomUUID(), new byte[] {3}, "SUGGEST_TEXT_KEEP_VOICE", false);
        var originalCalls = List.copyOf(state.calls);
        submitFailure(f, session, first, new byte[] {1}, "SUGGEST_TEXT_KEEP_VOICE", true);
        assertThat(state.calls).containsExactlyElementsOf(originalCalls);
        assertThat(voiceFailureCount(f)).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "select failure_count_after from roleplay_voice_input_failure where session_id=? and idempotency_key=?",
                                Integer.class,
                                f.session(),
                                first))
                .isEqualTo(1);
    }

    @Test
    void recommendingTextDoesNotBlockAnotherSuccessfulVoiceInputOrResetCount() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.lowConfidence = true;
        for (int i = 0; i < 3; i++)
            submitFailure(
                    f,
                    session,
                    UUID.randomUUID(),
                    new byte[] {(byte) i},
                    i == 2 ? "SUGGEST_TEXT_KEEP_VOICE" : "REQUEST_NEW_INPUT",
                    false);
        state.lowConfidence = false;
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {4}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(count(f)).isEqualTo(1);
        assertThat(voiceFailureCount(f)).isEqualTo(3);
        state.lowConfidence = true;
        submitFailure(
                f, session, UUID.randomUUID(), new byte[] {5}, "SUGGEST_TEXT_KEEP_VOICE", false);
        assertThat(voiceFailureCount(f)).isEqualTo(4);
        assertThat(count(f)).isEqualTo(1);
    }

    private String submitFailure(
            RoleplayHttpFixtures.Fixture f,
            MockHttpSession session,
            UUID key,
            byte[] audio,
            String action,
            boolean replayed)
            throws Exception {
        return submitFailure(f, session, key, audio, action, replayed, "audio/webm");
    }

    private String submitFailure(
            RoleplayHttpFixtures.Fixture f,
            MockHttpSession session,
            UUID key,
            byte[] audio,
            String action,
            boolean replayed,
            String mime)
            throws Exception {
        return mvc.perform(request(f.session(), key, audio, mime).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"))
                .andExpect(jsonPath("$.data.action").value(action))
                .andExpect(
                        jsonPath("$.data.messages[0].text")
                                .value(RoleplayNoticeCatalog.REINPUT_TEXT))
                .andExpect(jsonPath("$.data.replayed").value(replayed))
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty())
                .andExpect(jsonPath("$.data.checkpointVersion").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private int voiceFailureCount(RoleplayHttpFixtures.Fixture f) {
        return jdbc
                .queryForList(
                        "select failure_count from roleplay_voice_input_state where session_id=?",
                        Integer.class,
                        f.session())
                .stream()
                .findFirst()
                .orElse(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptedInputCarriesVerifiedQualityToAnalysisAndReview(boolean text) throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.inputAssessment =
                new com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor
                        .Assessment(0.87, true, true, true);
        var request =
                text
                        ? textRequest(f.session(), UUID.randomUUID(), "private synthetic text")
                        : request(
                                f.session(), UUID.randomUUID(), new byte[] {1, 2, 3}, "audio/webm");
        mvc.perform(request.session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        for (var call : List.of(state.llmRequests.get(0), state.llmRequests.get(2))) {
            var turn = json.readTree(call.userPrompt()).path("learner_turn");
            assertThat(turn.path("input_type").asString()).isEqualTo(text ? "TEXT" : "VOICE");
            assertThat(turn.path("input_quality").path("status").asString())
                    .isEqualTo("ANALYZABLE");
            assertThat(turn.path("input_quality").path("normalization_confidence").asDouble())
                    .isEqualTo(0.87);
            assertThat(turn.path("input_quality").path("meaning_preserved").asBoolean()).isTrue();
            assertThat(turn.path("safety_result").path("status").asString()).isEqualTo("SAFE");
            assertThat(turn.path("safety_result").path("personal_information_masked").asBoolean())
                    .isTrue();
            if (text) {
                assertThat(turn.path("input_quality").path("stt_confidence").isNull()).isTrue();
                assertThat(turn.path("input_quality").path("stt_confidence_threshold").isNull())
                        .isTrue();
            } else {
                assertThat(turn.path("input_quality").path("stt_confidence").asDouble())
                        .isEqualTo(0.9);
                assertThat(turn.path("input_quality").path("stt_confidence_threshold").asDouble())
                        .isEqualTo(0.40);
            }
            assertThat(call.userPrompt())
                    .doesNotContain("private synthetic text", "synthetic raw private input");
        }
        var stored =
                jdbc.queryForMap(
                        "select input_quality->>'input_type' as input_type, input_quality->>'normalization_confidence' as normalization_confidence, input_quality->>'personal_information_masked' as masked from roleplay_turn where session_id=?",
                        f.session());
        assertThat(stored)
                .containsEntry("input_type", text ? "TEXT" : "VOICE")
                .containsEntry("normalization_confidence", "0.87")
                .containsEntry("masked", "true");
        assertThat(count(f)).isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "true,missing,RETRY_REQUIRED", "false,missing,RETRY_REQUIRED",
        "true,meaning,REINPUT_REQUIRED", "false,meaning,REINPUT_REQUIRED",
        "true,unsafe,STOPPED", "false,unsafe,STOPPED",
        "true,confidence,RETRY_REQUIRED", "false,safety,RETRY_REQUIRED",
        "true,mask,RETRY_REQUIRED", "false,nonfinite,RETRY_REQUIRED"
    })
    void missingOrNegativeAssessmentCannotReachLearning(boolean text, String problem, String status)
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var before =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        state.inputAssessment =
                switch (problem) {
                    case "missing" -> null;
                    case "meaning" ->
                            new com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayInputProcessor.Assessment(0.9, false, true, false);
                    case "unsafe" ->
                            new com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayInputProcessor.Assessment(0.9, true, false, false);
                    case "confidence" ->
                            new com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayInputProcessor.Assessment(null, true, true, false);
                    case "safety" ->
                            new com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayInputProcessor.Assessment(0.9, true, null, false);
                    case "mask" ->
                            new com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayInputProcessor.Assessment(0.9, true, true, null);
                    case "nonfinite" ->
                            new com.neuringo.neuringobe.ai.application.roleplay
                                    .RoleplayInputProcessor.Assessment(
                                    Double.NaN, true, true, false);
                    default -> throw new AssertionError(problem);
                };
        var request =
                text
                        ? textRequest(f.session(), UUID.randomUUID(), "private synthetic text")
                        : request(
                                f.session(), UUID.randomUUID(), new byte[] {1, 2, 3}, "audio/webm");
        mvc.perform(request.session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(status));
        assertThat(state.llmRequests).isEmpty();
        assertThat(state.calls).doesNotContain("TTS", "PUBLISH");
        assertThat(count(f)).isZero();
        assertThat(recordCount(f)).isZero();
        boolean counted = !text && problem.equals("meaning");
        assertThat(voiceFailureCount(f)).isEqualTo(counted ? 1 : 0);
        if (counted) before.remove("last_activity_at");
        assertThat(
                        counted
                                ? learningSnapshot(f)
                                : jdbc.queryForMap(
                                        "select * from roleplay_session where session_id=?",
                                        f.session()))
                .isEqualTo(before);
        assertEmptySpool();
    }

    @Test
    void textInputStartsWithoutVoiceFailuresAndUsesRealGoalEvaluationWithoutStt() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.useServerGoalEvaluator = true;
        state.sttFailuresRemaining = 100;
        var key = UUID.randomUUID();
        String original = "private-learner-input";
        var result =
                mvc.perform(textRequest(f.session(), key, original).session(session).with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                        .andExpect(jsonPath("$.data.checkpointVersion").value(1))
                        .andExpect(jsonPath("$.data.replayed").value(false))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(result).doesNotContain(original);
        assertThat(state.calls).contains("INPUT").doesNotContain("STT");
        assertThat(state.llmRequests).hasSize(3);
        assertThat(state.llmRequests)
                .allSatisfy(request -> assertThat(request.userPrompt()).doesNotContain(original));
        assertThat(count(f)).isEqualTo(1);
        assertThat(voiceFailureCount(f)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_failure where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        var before = List.copyOf(state.calls);
        state.unavailable = true;
        mvc.perform(textRequest(f.session(), key, original).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty())
                .andExpect(jsonPath("$.data.messages[0].speech.available").value(false));
        mvc.perform(
                        textRequest(f.session(), key, original + " changed")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isConflict());
        assertThat(state.calls).containsExactlyElementsOf(before);
        assertThat(count(f)).isEqualTo(1);
    }

    @Test
    void switchingTextVoiceAndTextSharesTheSessionAndRejectsCrossModeKeyReuse() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        mvc.perform(textRequest(f.session(), key, "first text").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        mvc.perform(
                        request(f.session(), key, new byte[] {1}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isConflict());
        assertThat(state.calls).doesNotContain("STT");
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {2}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpointVersion").value(2));
        mvc.perform(
                        textRequest(f.session(), UUID.randomUUID(), "next text")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpointVersion").value(3));
        assertThat(state.calls.stream().filter("STT"::equals).count()).isEqualTo(1);
        assertThat(count(f)).isEqualTo(3);
    }

    @Test
    void threeVoiceFailuresCanBeFollowedByActualTextAndVoiceWithoutResettingTheCounter()
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.lowConfidence = true;
        var first = UUID.randomUUID();
        for (int i = 0; i < 3; i++)
            submitFailure(
                    f,
                    session,
                    i == 0 ? first : UUID.randomUUID(),
                    new byte[] {(byte) i},
                    i == 2 ? "SUGGEST_TEXT_KEEP_VOICE" : "REQUEST_NEW_INPUT",
                    false);
        mvc.perform(textRequest(f.session(), first, "changed kind").session(session).with(csrf()))
                .andExpect(status().isConflict());
        state.lowConfidence = false;
        long sttCalls = state.calls.stream().filter("STT"::equals).count();
        mvc.perform(
                        textRequest(f.session(), UUID.randomUUID(), "text after recommendation")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(state.calls.stream().filter("STT"::equals).count()).isEqualTo(sttCalls);
        assertThat(voiceFailureCount(f)).isEqualTo(3);
        mvc.perform(
                        request(f.session(), UUID.randomUUID(), new byte[] {9}, "audio/webm")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(voiceFailureCount(f)).isEqualTo(3);
        assertThat(count(f)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "processor-invalid"})
    void invalidTextRequestsNewInputWithoutVoiceFailureOrLearningRecord(String text)
            throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.invalidInput = true;
        mvc.perform(textRequest(f.session(), UUID.randomUUID(), text).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"))
                .andExpect(
                        jsonPath("$.data.messages[0].text")
                                .value(RoleplayNoticeCatalog.REINPUT_TEXT));
        assertThat(state.calls).doesNotContain("STT");
        assertThat(state.llmRequests).isEmpty();
        assertThat(count(f)).isZero();
        assertThat(recordCount(f)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_state where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_voice_input_failure where session_id=?",
                                Integer.class,
                                f.session()))
                .isZero();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "true,UNAVAILABLE", "true,EXCEPTION", "true,NULL",
        "false,UNAVAILABLE", "false,EXCEPTION", "false,NULL"
    })
    void inputCheckFailureReturnsRetryWithoutLearningWritesAndCanRecover(
            boolean text, String failure) throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        var before =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        state.inputFailure = failure;
        var response =
                mvc.perform(
                                (text
                                                ? textRequest(
                                                        f.session(), key, "synthetic-private-text")
                                                : request(
                                                        f.session(),
                                                        key,
                                                        new byte[] {1, 2, 3},
                                                        "audio/webm"))
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.status").value("RETRY_REQUIRED"))
                        .andExpect(jsonPath("$.data.action").value("RETRY_TURN"))
                        .andExpect(
                                jsonPath("$.data.messages[0].text")
                                        .value("test evaluation retry notice"))
                        .andExpect(jsonPath("$.data.acceptedInput").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response).doesNotContain("synthetic-private", "provider-detail");
        assertThat(state.calls)
                .containsExactlyElementsOf(
                        text ? List.of("CONTEXT", "INPUT") : List.of("CONTEXT", "STT", "INPUT"));
        assertThat(state.llmRequests).isEmpty();
        assertThat(state.goalDecisions).hasValue(0);
        assertThat(count(f)).isZero();
        assertThat(recordCount(f)).isZero();
        assertThat(voiceFailureCount(f)).isZero();
        for (var table :
                List.of(
                        "roleplay_voice_input_state",
                        "roleplay_voice_input_failure",
                        "roleplay_goal_assessment")) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from " + table + " where session_id=?",
                                    Integer.class,
                                    f.session()))
                    .isZero();
        }
        assertThat(
                        jdbc.queryForMap(
                                "select * from roleplay_session where session_id=?", f.session()))
                .isEqualTo(before);
        assertEmptySpool();

        state.inputFailure = null;
        mvc.perform(
                        (text
                                        ? textRequest(f.session(), key, "synthetic-private-text")
                                        : request(
                                                f.session(),
                                                key,
                                                new byte[] {1, 2, 3},
                                                "audio/webm"))
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.checkpointVersion").value(1));
        assertThat(count(f)).isEqualTo(1);
        assertThat(voiceFailureCount(f)).isZero();
        assertEmptySpool();
    }

    @Test
    void unsafeTextStopsBeforeLlmAndDoesNotExposeTheInput() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        state.unsafe = true;
        var result =
                mvc.perform(
                                textRequest(f.session(), UUID.randomUUID(), "private-unsafe-input")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("STOPPED"))
                        .andExpect(jsonPath("$.data.action").value("STOP_DIALOGUE"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(result).doesNotContain("private-unsafe-input", "private teacher");
        assertThat(state.calls).doesNotContain("STT");
        assertThat(state.llmRequests).isEmpty();
        assertThat(count(f)).isZero();
    }

    @Test
    void textLimitUsesConfiguredCodePointCountAndDoesNotInventAProductDefault() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        mvc.perform(
                        textRequest(f.session(), UUID.randomUUID(), "x".repeat(65))
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REINPUT_REQUIRED"));
        assertThat(state.calls).isEmpty();
        mvc.perform(
                        textRequest(f.session(), UUID.randomUUID(), "\uD83D\uDE00".repeat(64))
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(count(f)).isEqualTo(1);
    }

    @Test
    void textEndpointEnforcesChildOwnershipCsrfAndRequiredRequestFields() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var other = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        mvc.perform(textRequest(f.session(), UUID.randomUUID(), "text").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(textRequest(f.session(), UUID.randomUUID(), "text").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(
                        textRequest(other.session(), UUID.randomUUID(), "text")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/roleplay-sessions/{id}/text-inputs", f.session())
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/roleplay-sessions/{id}/text-inputs", f.session())
                                .session(session)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(state.calls).isEmpty();
    }

    @Test
    void concurrentTextDuplicateReturnsProcessingWithoutStartingAnotherPipeline() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.blockInput = true;
        try (var clients = Executors.newSingleThreadExecutor()) {
            var first =
                    clients.submit(
                            () ->
                                    mvc.perform(
                                                    textRequest(f.session(), key, "same text")
                                                            .session(session)
                                                            .with(csrf()))
                                            .andReturn());
            try {
                assertThat(state.entered.await(1, TimeUnit.SECONDS)).isTrue();
                mvc.perform(
                                textRequest(f.session(), key, "same text")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.status").value("PROCESSING"));
                mvc.perform(
                                textRequest(f.session(), key, "different text")
                                        .session(session)
                                        .with(csrf()))
                        .andExpect(status().isConflict());
                assertThat(state.calls.stream().filter("INPUT"::equals).count()).isEqualTo(1);
            } finally {
                state.release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(count(f)).isEqualTo(1);
        assertThat(state.calls).doesNotContain("STT");
    }

    @Test
    void textSaveFailureRollsBackAndTheSameKeyCanBeRetried() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        var before =
                jdbc.queryForMap("select * from roleplay_session where session_id=?", f.session());
        var constraint = "test_text_write_" + f.session().toString().replace("-", "");
        jdbc.execute(
                "alter table roleplay_turn add constraint "
                        + constraint
                        + " check (session_id <> '"
                        + f.session()
                        + "'::uuid) not valid");
        try {
            mvc.perform(textRequest(f.session(), key, "same text").session(session).with(csrf()))
                    .andExpect(status().isInternalServerError());
            assertThat(count(f)).isZero();
            assertThat(
                            jdbc.queryForMap(
                                    "select * from roleplay_session where session_id=?",
                                    f.session()))
                    .isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from roleplay_goal_assessment where session_id=?",
                                    Integer.class,
                                    f.session()))
                    .isZero();
            assertThat(recordCount(f)).isZero();
        } finally {
            jdbc.execute("alter table roleplay_turn drop constraint " + constraint);
        }
        mvc.perform(textRequest(f.session(), key, "same text").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.checkpointVersion").value(1));
        assertThat(count(f)).isEqualTo(1);
        assertThat(voiceFailureCount(f)).isZero();
        assertThat(state.calls).doesNotContain("STT");
    }

    @Test
    void completedTextResultReplaysAfterCanonicalDeletionAndBlocksNewInput() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var session = enter(f);
        var key = UUID.randomUUID();
        state.activityGoalAchieved = true;
        mvc.perform(textRequest(f.session(), key, "final text").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
        assertThat(
                        jdbc.queryForObject(
                                "select canonical_utterance from roleplay_turn where session_id=?",
                                String.class,
                                f.session()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select input_quality::text from roleplay_turn where session_id=?",
                                String.class,
                                f.session()))
                .isNull();
        var before = List.copyOf(state.calls);
        state.unavailable = true;
        mvc.perform(textRequest(f.session(), key, "final text").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.acceptedInput").isEmpty());
        mvc.perform(
                        textRequest(f.session(), UUID.randomUUID(), "new text")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isConflict());
        assertThat(state.calls).containsExactlyElementsOf(before);
        assertThat(count(f)).isEqualTo(1);
        assertThat(recordCount(f)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder textRequest(
            UUID session, UUID key, String text) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        return post("/api/v1/roleplay-sessions/{id}/text-inputs", session)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("text", text)));
    }

    private java.util.Map<String, Object> learningSnapshot(RoleplayHttpFixtures.Fixture f) {
        var values =
                new java.util.HashMap<>(
                        jdbc.queryForMap(
                                "select * from roleplay_session where session_id=?", f.session()));
        values.remove("last_activity_at");
        return values;
    }

    private int recordCount(RoleplayHttpFixtures.Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from learning_record where activity_id=?",
                Integer.class,
                f.activity());
    }

    private int canonicalCount(RoleplayHttpFixtures.Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=? and canonical_utterance is not null",
                Integer.class,
                f.session());
    }

    private java.util.Map<String, Object> completionSnapshot(RoleplayHttpFixtures.Fixture f) {
        return jdbc.queryForMap(
                "select a.completed_at, a.reward_issued_at, r.record_id, s.row_version from activity a join learning_record r on r.activity_id=a.activity_id join roleplay_session s on s.activity_id=a.activity_id where s.session_id=?",
                f.session());
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
        return enter(code);
    }

    private MockHttpSession enter(String code) throws Exception {
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
        try (var files = Files.walk(UPLOADS)) {
            assertThat(
                            files.filter(Files::isRegularFile)
                                    .filter(
                                            path ->
                                                    path.getFileName()
                                                                    .toString()
                                                                    .startsWith("roleplay-upload-")
                                                            || path.getFileName()
                                                                    .toString()
                                                                    .startsWith("upload_"))
                                    .count())
                    .isZero();
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
