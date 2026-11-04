package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.roleplay.application.*;
import com.neuringo.neuringobe.roleplay.service.ServerRoleplayActivityGoalEvaluator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 외부 통신 없이 음성 요청을 검증하는 테스트용 제공자다. */
@TestConfiguration(proxyBeanMethods = false)
public class RoleplayHttpTestConfiguration {
    @Bean
    public RoleplayAudioInspector roleplayAudioInspector() {
        return (file, format, deadline) -> deadline.requireActive();
    }

    public static class State {
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public final List<LlmRequest> llmRequests = new CopyOnWriteArrayList<>();
        private final Map<AiOperation, Queue<AiFailureType>> llmFailures =
                new ConcurrentHashMap<>();
        private final Queue<String> candidateTexts = new ConcurrentLinkedQueue<>();
        private final Queue<String> evaluations = new ConcurrentLinkedQueue<>();
        public boolean unavailable, unsafe, lowConfidence, blockStt, blockInput;
        public boolean noSpeech, unknownConfidence, invalidInput, inspectionUnavailable;
        public boolean inspectionTooLong;
        public String inputFailure;
        public RoleplayInputProcessor.Assessment inputAssessment =
                new RoleplayInputProcessor.Assessment(0.93, true, true, false);
        public int sttFailuresRemaining;
        public Double sttConfidence;
        public com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput.InputQuality
                lastInputQuality;
        public boolean activityGoalAchieved, goalDecisionUnavailable, goalCalledInTransaction;
        public boolean useServerGoalEvaluator, changeContextAfterSpeech;
        public GoalObservationInput contextObservations;
        public boolean omitObservationAnalysis, omitObservationReview, unsupportedObservationReview;
        public final AtomicInteger goalDecisions = new AtomicInteger();
        public CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);

        public void failNextCall(AiOperation operation, AiFailureType failure) {
            llmFailures
                    .computeIfAbsent(operation, ignored -> new ConcurrentLinkedQueue<>())
                    .add(failure);
        }

        public void rejectNextCandidate(String text) {
            candidateTexts.add(text);
            evaluations.add(
                    RoleplayFixtures.evaluationJson(
                            "REGENERATE",
                            "RESPONSE_GENERATION",
                            false,
                            0,
                            "[\"ANSWER_TOO_DIRECT\"]",
                            "{\"keep\":[\"감정 탐색\"],\"change\":[\"정답을 알려주지 않기\"],"
                                    + "\"avoid\":[],\"required\":[]}"));
        }

        public void reset() {
            calls.clear();
            llmRequests.clear();
            llmFailures.clear();
            candidateTexts.clear();
            evaluations.clear();
            unavailable = false;
            unsafe = false;
            lowConfidence = false;
            noSpeech = false;
            inspectionUnavailable = false;
            inspectionTooLong = false;
            unknownConfidence = false;
            invalidInput = false;
            inputFailure = null;
            inputAssessment = new RoleplayInputProcessor.Assessment(0.93, true, true, false);
            sttFailuresRemaining = 0;
            sttConfidence = null;
            lastInputQuality = null;
            blockStt = false;
            blockInput = false;
            activityGoalAchieved = false;
            goalDecisionUnavailable = false;
            goalCalledInTransaction = false;
            useServerGoalEvaluator = false;
            changeContextAfterSpeech = false;
            contextObservations = null;
            omitObservationAnalysis = false;
            omitObservationReview = false;
            unsupportedObservationReview = false;
            goalDecisions.set(0);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            finished = new CountDownLatch(1);
        }
    }

    @Bean
    State httpState() {
        return new State();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    RoleplayActivityGoalEvaluator httpGoalEvaluator(
            State state, ServerRoleplayActivityGoalEvaluator server) {
        return (command, approved, deadline) -> {
            state.goalDecisions.incrementAndGet();
            state.lastInputQuality = command.inputQuality();
            state.goalCalledInTransaction =
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive();
            if (state.goalDecisionUnavailable) {
                throw new RoleplayActivityGoalEvaluator.Unavailable();
            }
            if (state.useServerGoalEvaluator) return server.evaluate(command, approved, deadline);
            return com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                    .forCommand(command, state.activityGoalAchieved);
        };
    }

    @Bean
    @org.springframework.context.annotation.Primary
    RoleplayContextSource httpContext(
            State state,
            JdbcTemplate jdbc,
            com.neuringo.neuringobe.roleplay.config.RoleplayProgressProperties progress) {
        return (key, sourceDeadline) -> {
            state.calls.add("CONTEXT");
            if (state.unavailable) return Optional.empty();
            int turn =
                    jdbc.queryForObject(
                                    "select last_turn_number from roleplay_session where session_id=?",
                                    Integer.class,
                                    key.sessionId())
                            + 1;
            return Optional.of(
                    new RoleplayContextSource.Snapshot(
                            key,
                            "test approval",
                            RoleplayFixtures.input("").scenario(),
                            new RoleplayTurnInput.State(
                                    RoleplayFixtures.MG_EMOTION,
                                    "S1",
                                    jdbc.queryForObject(
                                            "select count(*)+1 from roleplay_turn where session_id=? and input_micro_goal_id=?",
                                            Integer.class,
                                            key.sessionId(),
                                            RoleplayFixtures.MG_EMOTION),
                                    jdbc.queryForObject(
                                            "select count(*) from roleplay_turn where session_id=? and micro_goal_id=? and response_type in ('GUIDING_QUESTION','REDIRECT','CONFIRMATION')",
                                            Integer.class,
                                            key.sessionId(),
                                            RoleplayFixtures.MG_EMOTION),
                                    jdbc.queryForObject(
                                            "select exists(select 1 from roleplay_turn where session_id=? and micro_goal_id=? and response_type='ANSWER_REVEAL')",
                                            Boolean.class,
                                            key.sessionId(),
                                            RoleplayFixtures.MG_EMOTION),
                                    turn,
                                    progress.configured() ? progress.maximumTurnCount() : 77,
                                    9),
                            List.of(),
                            observations(state, jdbc, key.sessionId()),
                            com.neuringo.neuringobe.roleplay.application
                                    .RoleplayObservationTestPlans.emptyResponseHistory(
                                    RoleplayFixtures.input("").scenario())));
        };
    }

    private GoalObservationInput observations(
            State state, JdbcTemplate jdbc, java.util.UUID sessionId) {
        if (state.contextObservations != null) return state.contextObservations;
        var template =
                RoleplayObservationTestPlans.inputForScenario(
                        RoleplayFixtures.input("").scenario());
        if (!state.useServerGoalEvaluator) return template;
        var history =
                jdbc.query(
                        "select turn_id, micro_goal_id, criterion_reference, observation_status from roleplay_goal_observation where session_id=? order by turn_id, micro_goal_id, criterion_reference",
                        (rs, n) ->
                                new GoalObservationOutput.Observation(
                                        new GoalObservationOutput.Reference(
                                                rs.getObject("turn_id", java.util.UUID.class),
                                                rs.getObject("micro_goal_id", java.util.UUID.class),
                                                rs.getString("criterion_reference")),
                                        GoalObservationOutput.Status.valueOf(
                                                rs.getString("observation_status"))),
                        sessionId);
        return com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                .withSyntheticObservationContexts(template.approvedCriteria(), history);
    }

    @Bean
    RoleplayInputProcessor httpInput(State state) {
        return text -> {
            state.calls.add("INPUT");
            if ("EXCEPTION".equals(state.inputFailure))
                throw new IllegalStateException("synthetic-private-input-and-provider-detail");
            if ("NULL".equals(state.inputFailure)) return null;
            if ("UNAVAILABLE".equals(state.inputFailure))
                return new RoleplayInputProcessor.Unavailable();
            if (state.blockInput) {
                state.entered.countDown();
                try {
                    if (!state.release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Synthetic input release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Synthetic input interrupted");
                }
            }
            return state.unsafe
                    ? new RoleplayInputProcessor.Unsafe()
                    : state.invalidInput
                            ? new RoleplayInputProcessor.Invalid()
                            : new RoleplayInputProcessor.Accepted(
                                    "친구가 울고 있어", state.inputAssessment);
        };
    }

    @Bean
    SpeechToTextProvider httpStt(State state) {
        return request -> {
            state.calls.add("STT");
            if (state.blockStt) {
                state.entered.countDown();
                try {
                    boolean interrupted = false;
                    while (true) {
                        try {
                            if (!state.release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test release timed out");
                            break;
                        } catch (InterruptedException ex) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                } finally {
                    state.finished.countDown();
                }
            }
            return success(
                    new SpeechTranscription(
                            "synthetic raw private input",
                            state.lowConfidence ? 0.39 : 0.9,
                            "test-vendor",
                            "test-model",
                            null,
                            null),
                    request.traceContext(),
                    AiOperation.SPEECH_TRANSCRIPTION);
        };
    }

    @Bean
    TextToSpeechProvider httpTts(State state) {
        return request -> {
            state.calls.add("TTS");
            if (state.changeContextAfterSpeech) {
                var supplied =
                        RoleplayObservationTestPlans.inputForScenario(
                                RoleplayFixtures.input("").scenario());
                var criterion = supplied.approvedCriteria().getFirst();
                state.contextObservations =
                        com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                                .withSyntheticObservationContexts(
                                        supplied.approvedCriteria(),
                                        List.of(
                                                new GoalObservationOutput.Observation(
                                                        new GoalObservationOutput.Reference(
                                                                java.util.UUID.randomUUID(),
                                                                criterion.microGoalId(),
                                                                criterion.reference()),
                                                        GoalObservationOutput.Status.IMPLICIT)));
            }
            return success(
                    new SynthesizedSpeech(
                            new byte[] {7, 8},
                            AudioFormat.MP3,
                            "test-vendor",
                            "test-model",
                            "test-voice"),
                    request.traceContext(),
                    AiOperation.SPEECH_SYNTHESIS);
        };
    }

    @Bean
    LlmProvider httpLlm(State state, ObjectMapper mapper) {
        return request -> {
            state.calls.add(request.operation().name());
            state.llmRequests.add(request);
            Queue<AiFailureType> failures = state.llmFailures.get(request.operation());
            AiFailureType failure = failures == null ? null : failures.poll();
            if (failure != null)
                return new AiCallResult.Failure<>(
                        new AiFailure(failure, null), llmMetadata(request));
            var turnState = mapper.readTree(request.userPrompt()).path("state");
            int maximum = turnState.path("maximum_turn_count").asInt();
            boolean maximumReached = maximum > 0 && turnState.path("turn_count").asInt() >= maximum;
            String json =
                    switch (request.operation()) {
                        case CAUSE_ANALYSIS ->
                                RoleplayFixtures.analysisJson(
                                        maximumReached ? "CLOSE_ROLEPLAY" : "PROBE_EMOTION",
                                        RoleplayFixtures.MG_EMOTION,
                                        "S1");
                        case RESPONSE_GENERATION -> {
                            String text = state.candidateTexts.poll();
                            String candidate =
                                    RoleplayFixtures.candidateJson(
                                            text == null
                                                    ? (maximumReached
                                                            ? "함께해 줘서 고마워."
                                                            : "친구는 어떤 기분일까?")
                                                    : text,
                                            maximumReached ? "CLOSING" : "GUIDING_QUESTION");
                            if (maximumReached)
                                candidate = candidate.replace("PROBE_EMOTION", "CLOSE_ROLEPLAY");
                            yield candidate.replace(
                                    "\"previous_failed_candidate_ids\":[]",
                                    "\"previous_failed_candidate_ids\":"
                                            + mapper.readTree(request.userPrompt())
                                                    .get("previous_failed_candidate_ids"));
                        }
                        case RESPONSE_EVALUATION -> {
                            String evaluation = state.evaluations.poll();
                            yield evaluation == null
                                    ? RoleplayFixtures.evaluationJson(
                                            "PASS", null, true, 0, "[]", "null")
                                    : evaluation;
                        }
                        default -> throw new AssertionError("Unexpected operation");
                    };
            if (request.operation() == AiOperation.CAUSE_ANALYSIS && state.activityGoalAchieved) {
                var body =
                        mapper.readValue(
                                json,
                                new tools.jackson.core.type.TypeReference<
                                        java.util.Map<String, Object>>() {});
                var old =
                        mapper.readValue(
                                mapper.writeValueAsString(body.get("goal_observation")),
                                GoalObservationOutput.Analysis.class);
                var approved =
                        old.observations().stream()
                                .map(
                                        o ->
                                                new GoalObservationOutput.Observation(
                                                        o.reference(),
                                                        GoalObservationOutput.Status.EXPLICIT))
                                .toList();
                var basis = new java.util.ArrayList<GoalObservationOutput.Reference>();
                approved.forEach(o -> basis.add(o.reference()));
                var supplied =
                        mapper.readValue(
                                mapper.readTree(request.userPrompt())
                                        .get("goal_observation_context")
                                        .toString(),
                                GoalObservationInput.class);
                supplied.confirmedObservations().forEach(o -> basis.add(o.reference()));
                body.put(
                        "goal_observation",
                        new GoalObservationOutput.Analysis(
                                old.schemaVersion(),
                                old.analysisId(),
                                approved,
                                new GoalObservationOutput.Assessment(
                                        GoalObservationOutput.ActivityDecision.ACHIEVED, basis)));
                json = mapper.writeValueAsString(body);
            }
            json =
                    json.replace(
                            RoleplayFixtures.TURN_ID.toString(),
                            request.traceContext().turnId().toString());
            if (request.traceContext().candidateId() != null)
                json =
                        json.replace(
                                RoleplayFixtures.CANDIDATE_ID.toString(),
                                request.traceContext().candidateId().toString());
            if ((request.operation() == AiOperation.CAUSE_ANALYSIS && state.omitObservationAnalysis)
                    || (request.operation() == AiOperation.RESPONSE_EVALUATION
                            && (state.omitObservationReview
                                    || state.unsupportedObservationReview))) {
                var changed =
                        mapper.readValue(
                                json,
                                new tools.jackson.core.type.TypeReference<
                                        java.util.Map<String, Object>>() {});
                if (request.operation() == AiOperation.CAUSE_ANALYSIS)
                    changed.remove("goal_observation");
                else if (state.omitObservationReview) changed.remove("goal_observation_review");
                else {
                    var review =
                            mapper.readValue(
                                    mapper.writeValueAsString(
                                            changed.get("goal_observation_review")),
                                    new tools.jackson.core.type.TypeReference<
                                            java.util.Map<String, Object>>() {});
                    review.put("observations_supported", false);
                    changed.put("goal_observation_review", review);
                }
                json = mapper.writeValueAsString(changed);
            }
            return new AiCallResult.Success<>(
                    new LlmCompletion(
                            RoleplayFixtures.bindAnalysis(
                                    json, request.traceContext().analysisId()),
                            "test-vendor",
                            "test-model",
                            "stop",
                            null,
                            null),
                    llmMetadata(request));
        };
    }

    private static AiCallMetadata llmMetadata(LlmRequest request) {
        return new AiCallMetadata(
                request.traceContext().requestId(),
                request.operation(),
                "test-vendor",
                "test-model",
                request.promptVersion(),
                request.responseSchemaVersion(),
                request.policyVersion(),
                0,
                request.currentAttempt(),
                null,
                null,
                "stop");
    }

    @Bean
    RoleplaySpeechDelivery httpSpeech(State state) {
        return (id, speech) -> {
            state.calls.add("PUBLISH");
            return Optional.empty();
        };
    }

    private static <T> AiCallResult<T> success(
            T data, AiTraceContext trace, AiOperation operation) {
        return new AiCallResult.Success<>(
                data,
                new AiCallMetadata(
                        trace.requestId(),
                        operation,
                        "test-vendor",
                        "test-model",
                        "test/v1",
                        "v1",
                        null,
                        0,
                        1,
                        null,
                        null,
                        "stop"));
    }
}
