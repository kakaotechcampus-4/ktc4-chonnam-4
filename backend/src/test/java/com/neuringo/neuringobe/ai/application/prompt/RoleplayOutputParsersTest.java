package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplayOutputParsersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RoleplayPromptFactory factory = new RoleplayPromptFactory(JSON, null);

    @Nested
    class Analysis {

        private final LlmOutputParser<AnalysisResult> parser =
                factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null).parser();

        @Test
        void acceptsValidOutputAndIgnoresReasoningFields() {
            AnalysisResult result = parser.parse(analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"));

            assertThat(result.learningState()).isEqualTo("PARTIAL_UNDERSTANDING");
            assertThat(result.nextStrategy().targetMicroGoalId()).isEqualTo(MG_EMOTION);
        }

        @Test
        void stripsMarkdownCodeFence() {
            String fenced = "```json\n" + analysisJson("PROBE_EMOTION", MG_EMOTION, "S1") + "\n```";

            assertThat(parser.parse(fenced).turnId()).isEqualTo(TURN_ID);
        }

        @Test
        void rejectsOtherTurn() {
            String other =
                    analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")
                            .replace(TURN_ID.toString(), UUID.randomUUID().toString());

            assertThatThrownBy(() -> parser.parse(other))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsTargetOutsideScenario() {
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            analysisJson("PROBE_EMOTION", UUID.randomUUID(), "S1")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsUnknownCodes() {
            assertThatThrownBy(() -> parser.parse(analysisJson("GUESS", MG_EMOTION, "S1")))
                    .isInstanceOf(InvalidLlmOutputException.class);
            assertThatThrownBy(() -> parser.parse(analysisJson("PROBE_EMOTION", MG_EMOTION, "S4")))
                    .isInstanceOf(InvalidLlmOutputException.class);
            String badState =
                    analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")
                            .replace("PARTIAL_UNDERSTANDING", "CONFUSED");
            assertThatThrownBy(() -> parser.parse(badState))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsConfidenceOutOfRange() {
            String bad =
                    analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")
                            .replace("\"analysis_confidence\":0.9", "\"analysis_confidence\":1.4");

            assertThatThrownBy(() -> parser.parse(bad))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }
    }

    @Nested
    class Candidate {

        private final AnalysisResult analysis =
                factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null)
                        .parser()
                        .parse(analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"));

        private LlmOutputParser<CandidateResponse> parser(GenerationRetry retry) {
            return factory.responseGeneration(
                            trace(ANALYSIS_ID, null),
                            attempts(),
                            1,
                            input("울어"),
                            analysis,
                            CANDIDATE_ID,
                            retry)
                    .parser();
        }

        @Test
        void acceptsCandidateFollowingTheAnalysis() {
            CandidateResponse result =
                    parser(null)
                            .parse(
                                    candidateJson(
                                            "친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?", "GUIDING_QUESTION"));

            assertThat(result.candidateId()).isEqualTo(CANDIDATE_ID);
        }

        @Test
        void rejectsStrategyOrLevelOtherThanAnalysis() {
            String otherStrategy =
                    candidateJson("친구는 왜 울었을까?", "GUIDING_QUESTION")
                            .replace("PROBE_EMOTION", "PROBE_CAUSE");
            String otherLevel =
                    candidateJson("친구는 기쁠까, 속상할까?", "GUIDING_QUESTION").replace("\"S1\"", "\"S2\"");

            assertThatThrownBy(() -> parser(null).parse(otherStrategy))
                    .isInstanceOf(InvalidLlmOutputException.class);
            assertThatThrownBy(() -> parser(null).parse(otherLevel))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "친구가 울고 있어.",
                    "친구는 왜 울었어? 어떤 기분이야?",
                })
        void questionResponseNeedsExactlyOneQuestion(String text) {
            assertThatThrownBy(() -> parser(null).parse(candidateJson(text, "GUIDING_QUESTION")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void answerRevealMustNotAsk() {
            assertThatThrownBy(
                            () ->
                                    parser(null)
                                            .parse(
                                                    candidateJson(
                                                            "친구는 아팠을 거야. 알겠지?", "ANSWER_REVEAL")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsTooLongText() {
            String text = "친구가 ".repeat(30) + "어떤 기분일까?";

            assertThatThrownBy(() -> parser(null).parse(candidateJson(text, "GUIDING_QUESTION")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsRepeatedFailedCandidateAndMissingFailedIds() {
            UUID failedId = UUID.randomUUID();
            GenerationRetry retry =
                    new GenerationRetry(
                            List.of(new GenerationRetry.FailedCandidate(failedId, "친구는 어떤 기분일까?")),
                            List.of("REPEATED_QUESTION"),
                            new RevisionInstruction(null, List.of("다른 질문으로"), null, null));
            String repeated =
                    candidateJson("친구는  어떤 기분일까?", "GUIDING_QUESTION")
                            .replace(
                                    "\"previous_failed_candidate_ids\":[]",
                                    "\"previous_failed_candidate_ids\":[\"" + failedId + "\"]");
            String missingIds = candidateJson("몸은 어떤 느낌일까?", "GUIDING_QUESTION");

            assertThatThrownBy(() -> parser(retry).parse(repeated))
                    .isInstanceOf(InvalidLlmOutputException.class);
            assertThatThrownBy(() -> parser(retry).parse(missingIds))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }
    }

    @Nested
    class Evaluation {

        private final LlmOutputParser<EvaluationResult> parser = evaluationParser();

        @Test
        void acceptsPass() {
            EvaluationResult result =
                    parser.parse(evaluationJson("PASS", null, true, 0, "[]", "null"));

            assertThat(result.canDeliver()).isTrue();
        }

        @Test
        void acceptsRegenerateWithRevision() {
            EvaluationResult result =
                    parser.parse(
                            evaluationJson(
                                    "REGENERATE",
                                    "RESPONSE_GENERATION",
                                    false,
                                    0,
                                    "[\"MULTIPLE_QUESTIONS\"]",
                                    "{\"keep\":[\"감정 탐색\"],\"change\":[\"질문 하나\"],"
                                            + "\"avoid\":[],\"required\":[]}"));

            assertThat(result.decision()).isEqualTo(EvaluationDecision.REGENERATE);
            assertThat(result.canDeliver()).isFalse();
        }

        @Test
        void rejectsPassWithFailureOrUnsafe() {
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            evaluationJson(
                                                    "PASS",
                                                    null,
                                                    true,
                                                    0,
                                                    "[\"TOO_LONG\"]",
                                                    "null")))
                    .isInstanceOf(InvalidLlmOutputException.class);
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            evaluationJson("PASS", null, false, 0, "[]", "null")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsDecisionsReservedForOrchestrator() {
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            evaluationJson(
                                                    "SAFE_FALLBACK",
                                                    "SAFE_FALLBACK",
                                                    false,
                                                    0,
                                                    "[\"NOT_ANSWERABLE\"]",
                                                    "null")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsRetryTargetThatDoesNotFitDecision() {
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            evaluationJson(
                                                    "REANALYZE",
                                                    "RESPONSE_GENERATION",
                                                    false,
                                                    0,
                                                    "[\"UNSUPPORTED_CAUSE_ANALYSIS\"]",
                                                    "null")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        @Test
        void rejectsUnknownFailureCodeAndUndercountedCritical() {
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            evaluationJson(
                                                    "REANALYZE",
                                                    "CAUSE_ANALYSIS",
                                                    false,
                                                    0,
                                                    "[\"SOMETHING_ELSE\"]",
                                                    "null")))
                    .isInstanceOf(InvalidLlmOutputException.class);
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            evaluationJson(
                                                    "REANALYZE",
                                                    "CAUSE_ANALYSIS",
                                                    false,
                                                    0,
                                                    "[\"REINFORCES_INCORRECT_ANSWER\"]",
                                                    "null")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }
    }

    @Test
    void contractViolationBecomesRetryableInvalidOutputFormat() {
        String wrongTurn =
                analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")
                        .replace(TURN_ID.toString(), UUID.randomUUID().toString());
        PreparedPrompt<AnalysisResult> prompt =
                factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null);
        AiCallMetadata metadata =
                new AiCallMetadata(
                        REQUEST_ID,
                        prompt.request().operation(),
                        "fake",
                        "model",
                        prompt.request().promptVersion(),
                        prompt.request().responseSchemaVersion(),
                        null,
                        1,
                        1,
                        null,
                        null,
                        "stop");
        StructuredLlmExecutor executor =
                new StructuredLlmExecutor(
                        request ->
                                new AiCallResult.Success<>(
                                        new LlmCompletion(wrongTurn, "fake", "model", "stop", 1, 1),
                                        metadata));

        AiCallResult<AnalysisResult> result = executor.execute(prompt.request(), prompt.parser());

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        AiCallResult.Failure<AnalysisResult> failure =
                (AiCallResult.Failure<AnalysisResult>) result;
        assertThat(failure.failure().type()).isEqualTo(AiFailureType.INVALID_OUTPUT_FORMAT);
        assertThat(failure.failure().retryable()).isTrue();
    }

    private LlmOutputParser<EvaluationResult> evaluationParser() {
        AnalysisResult analysis =
                factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null)
                        .parser()
                        .parse(analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"));
        CandidateResponse candidate =
                factory.responseGeneration(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input("울어"),
                                analysis,
                                CANDIDATE_ID,
                                null)
                        .parser()
                        .parse(candidateJson("몸은 어떤 느낌일까?", "GUIDING_QUESTION"));
        return factory.responseEvaluation(
                        trace(ANALYSIS_ID, CANDIDATE_ID),
                        attempts(),
                        1,
                        input("울어"),
                        analysis,
                        candidate)
                .parser();
    }
}
