package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.application.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only providers: no production default or network access. */
@TestConfiguration(proxyBeanMethods = false)
public class RoleplayHttpTestConfiguration {
    public static class State {
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public boolean unavailable, unsafe, lowConfidence, blockStt;
        public CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);

        public void reset() {
            calls.clear();
            unavailable = false;
            unsafe = false;
            lowConfidence = false;
            blockStt = false;
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
    RoleplayContextSource httpContext(State state, JdbcTemplate jdbc) {
        return key -> {
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
                                    RoleplayFixtures.MG_EMOTION, "S1", 1, 0, false, turn, 77, 9),
                            List.of()));
        };
    }

    @Bean
    RoleplayInputProcessor httpInput(State state) {
        return text -> {
            state.calls.add("INPUT");
            return state.unsafe
                    ? new RoleplayInputProcessor.Unsafe()
                    : new RoleplayInputProcessor.Accepted("친구가 울고 있어");
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
    LlmProvider httpLlm(State state) {
        return request -> {
            state.calls.add(request.operation().name());
            String json =
                    switch (request.operation()) {
                        case CAUSE_ANALYSIS ->
                                RoleplayFixtures.analysisJson(
                                        "PROBE_EMOTION", RoleplayFixtures.MG_EMOTION, "S1");
                        case RESPONSE_GENERATION ->
                                RoleplayFixtures.candidateJson("친구는 어떤 기분일까?", "GUIDING_QUESTION");
                        case RESPONSE_EVALUATION ->
                                RoleplayFixtures.evaluationJson(
                                        "PASS", null, true, 0, "[]", "null");
                        default -> throw new AssertionError("Unexpected operation");
                    };
            json =
                    json.replace(
                            RoleplayFixtures.TURN_ID.toString(),
                            request.traceContext().turnId().toString());
            if (request.traceContext().candidateId() != null)
                json =
                        json.replace(
                                RoleplayFixtures.CANDIDATE_ID.toString(),
                                request.traceContext().candidateId().toString());
            return success(
                    new LlmCompletion(json, "test-vendor", "test-model", "stop", null, null),
                    request.traceContext(),
                    request.operation());
        };
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
