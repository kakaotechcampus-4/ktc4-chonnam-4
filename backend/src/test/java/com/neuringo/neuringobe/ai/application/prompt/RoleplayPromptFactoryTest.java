package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RoleplayPromptFactoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RoleplayPromptFactory factory =
            new RoleplayPromptFactory(JSON, "SUPPORT-MVP-001");

    @Test
    void causeAnalysisRequestCarriesVersionsAndTurnData() {
        PreparedPrompt<AnalysisResult> prompt =
                factory.causeAnalysis(trace(null, null), attempts(), 1, input("친구가 울고 있어."), null);

        LlmRequest request = prompt.request();
        assertThat(request.operation()).isEqualTo(AiOperation.CAUSE_ANALYSIS);
        assertThat(request.promptVersion()).isEqualTo("roleplay-cause-analysis/v1");
        assertThat(request.responseSchemaVersion()).isEqualTo("analysis-result/v1");
        assertThat(request.policyVersion()).isEqualTo("SUPPORT-MVP-001");
        assertThat(request.systemPrompt()).contains("원인 판단 단계");

        JsonNode message = JSON.readTree(request.userPrompt());
        assertThat(message.path("learner_turn").path("turn_id").asString())
                .isEqualTo(TURN_ID.toString());
        assertThat(message.path("learner_turn").path("canonical_utterance").asString())
                .isEqualTo("친구가 울고 있어.");
        assertThat(message.path("scenario").path("micro_goals")).hasSize(3);
        assertThat(message.path("state").path("current_support_level").asString()).isEqualTo("S1");
        assertThat(message.has("retry")).isFalse();
    }

    @Test
    void promptsNeverCarryTraceIdentifiers() {
        String analysis = analysisJson("PROBE_EMOTION", MG_EMOTION, "S1");
        AnalysisResult parsedAnalysis =
                factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null)
                        .parser()
                        .parse(analysis);
        CandidateResponse candidate =
                factory.responseGeneration(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input("울어"),
                                parsedAnalysis,
                                CANDIDATE_ID,
                                null)
                        .parser()
                        .parse(candidateJson("친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?", "GUIDING_QUESTION"));

        Stream.of(
                        factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null)
                                .request(),
                        factory.responseGeneration(
                                        trace(ANALYSIS_ID, null),
                                        attempts(),
                                        1,
                                        input("울어"),
                                        parsedAnalysis,
                                        CANDIDATE_ID,
                                        null)
                                .request(),
                        factory.responseEvaluation(
                                        trace(ANALYSIS_ID, CANDIDATE_ID),
                                        attempts(),
                                        1,
                                        input("울어"),
                                        parsedAnalysis,
                                        candidate)
                                .request())
                .forEach(
                        request ->
                                assertThat(request.userPrompt())
                                        .doesNotContain(REQUEST_ID.toString())
                                        .doesNotContain(ACTIVITY_ID.toString())
                                        .doesNotContain(CHILD_ID.toString())
                                        .doesNotContain(SESSION_ID.toString()));
    }

    @Test
    void reanalysisAddsRetryInstruction() {
        LlmRequest request =
                factory.causeAnalysis(
                                trace(null, null),
                                attempts(),
                                2,
                                input("친구가 사탕을 못 가져서 화났어."),
                                new AnalysisRetry(
                                        List.of("UNSUPPORTED_CAUSE_ANALYSIS"),
                                        List.of("아동이 실제로 말한 감정"),
                                        List.of("표현하지 않은 감정 원인")))
                        .request();

        JsonNode retry = JSON.readTree(request.userPrompt()).path("retry");
        assertThat(retry.path("failure_codes").get(0).asString())
                .isEqualTo("UNSUPPORTED_CAUSE_ANALYSIS");
        assertThat(retry.path("do_not_assume").get(0).asString()).isEqualTo("표현하지 않은 감정 원인");
        assertThat(request.currentAttempt()).isEqualTo(2);
    }

    @Test
    void regenerationCarriesFailedCandidatesAndRevision() {
        AnalysisResult analysis = parsedAnalysis();
        UUID failedId = UUID.randomUUID();
        GenerationRetry retry =
                new GenerationRetry(
                        List.of(
                                new GenerationRetry.FailedCandidate(
                                        failedId, "친구는 왜 울었고 어떤 기분이며 너라면 무엇을 해 줄래?")),
                        List.of("MULTIPLE_QUESTIONS", "TOO_DIFFICULT"),
                        new RevisionInstruction(
                                List.of("감정 탐색 목적"),
                                List.of("질문을 하나로 축소"),
                                List.of("여러 질문"),
                                List.of("짧은 문장")));

        LlmRequest request =
                factory.responseGeneration(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                2,
                                input("친구가 울고 있어."),
                                analysis,
                                CANDIDATE_ID,
                                retry)
                        .request();

        JsonNode message = JSON.readTree(request.userPrompt());
        assertThat(message.path("candidate_id").asString()).isEqualTo(CANDIDATE_ID.toString());
        assertThat(message.path("previous_failed_candidate_ids").get(0).asString())
                .isEqualTo(failedId.toString());
        assertThat(message.path("analysis_result").path("next_strategy").path("type").asString())
                .isEqualTo("PROBE_EMOTION");
        assertThat(
                        message.path("retry")
                                .path("revision_instruction")
                                .path("change")
                                .get(0)
                                .asString())
                .isEqualTo("질문을 하나로 축소");
        assertThat(request.operation()).isEqualTo(AiOperation.RESPONSE_GENERATION);
    }

    @Test
    void generationRetryRequiresFailedCandidatesCodesAndRevision() {
        List<GenerationRetry.FailedCandidate> failed =
                List.of(new GenerationRetry.FailedCandidate(UUID.randomUUID(), "친구는 어떤 기분일까?"));
        List<String> codes = List.of("REPEATED_QUESTION");
        RevisionInstruction revision =
                new RevisionInstruction(null, List.of("다른 질문으로"), null, null);

        assertThatThrownBy(() -> new GenerationRetry(List.of(), codes, revision))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationRetry(null, codes, revision))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GenerationRetry(failed, List.of(), revision))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationRetry(failed, null, revision))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GenerationRetry(failed, codes, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void evaluationIncludesAnalysisAndCandidate() {
        AnalysisResult analysis = parsedAnalysis();
        CandidateResponse candidate = parsedCandidate(analysis);

        LlmRequest request =
                factory.responseEvaluation(
                                trace(ANALYSIS_ID, CANDIDATE_ID),
                                attempts(),
                                1,
                                input("친구가 울고 있어."),
                                analysis,
                                candidate)
                        .request();

        JsonNode message = JSON.readTree(request.userPrompt());
        assertThat(message.path("candidate_response").path("text").asString())
                .isEqualTo("친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?");
        assertThat(message.path("analysis_result").path("learning_state").asString())
                .isEqualTo("PARTIAL_UNDERSTANDING");
        assertThat(request.promptVersion()).isEqualTo("roleplay-response-evaluation/v1");
    }

    @Test
    void rejectsMismatchedTurnOrCandidate() {
        AnalysisResult analysis = parsedAnalysis();
        CandidateResponse candidate = parsedCandidate(analysis);

        assertThatThrownBy(
                        () ->
                                factory.causeAnalysis(
                                        new com.neuringo.neuringobe.ai.application.model
                                                .AiTraceContext(
                                                REQUEST_ID,
                                                ACTIVITY_ID,
                                                null,
                                                null,
                                                null,
                                                null,
                                                SESSION_ID,
                                                UUID.randomUUID(),
                                                null,
                                                null),
                                        attempts(),
                                        1,
                                        input("울어"),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                factory.responseEvaluation(
                                        trace(ANALYSIS_ID, UUID.randomUUID()),
                                        attempts(),
                                        1,
                                        input("울어"),
                                        analysis,
                                        candidate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsOnlyRecentDialogueLines() {
        List<RoleplayTurnInput.DialogueLine> lines =
                java.util.stream.IntStream.range(0, 12)
                        .mapToObj(
                                i ->
                                        new RoleplayTurnInput.DialogueLine(
                                                RoleplayTurnInput.Speaker.AI, "line-" + i))
                        .toList();
        RoleplayTurnInput base = input("울어");
        RoleplayTurnInput input =
                new RoleplayTurnInput(base.scenario(), base.state(), base.learnerTurn(), lines);

        assertThat(input.recentDialogue()).hasSize(RoleplayTurnInput.MAX_DIALOGUE_LINES);
        assertThat(input.recentDialogue().getFirst().text()).isEqualTo("line-4");
    }

    @Test
    void inputRejectsUnknownCurrentGoalAndCodes() {
        RoleplayTurnInput base = input("울어");
        assertThatThrownBy(
                        () ->
                                new RoleplayTurnInput(
                                        base.scenario(),
                                        new RoleplayTurnInput.State(
                                                UUID.randomUUID(), "S1", 1, 0, false, 1, 6, 4),
                                        base.learnerTurn(),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new RoleplayTurnInput.State(MG_EMOTION, "S9", 1, 0, false, 1, 6, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemPromptsListEveryAllowedCode() {
        String analysis =
                factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null)
                        .request()
                        .systemPrompt();
        String generation =
                factory.responseGeneration(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input("울어"),
                                parsedAnalysis(),
                                CANDIDATE_ID,
                                null)
                        .request()
                        .systemPrompt();
        String evaluation =
                factory.responseEvaluation(
                                trace(ANALYSIS_ID, CANDIDATE_ID),
                                attempts(),
                                1,
                                input("울어"),
                                parsedAnalysis(),
                                parsedCandidate(parsedAnalysis()))
                        .request()
                        .systemPrompt();

        assertContainsAll(analysis, RoleplayCodes.RESPONSE_ACTS);
        assertContainsAll(analysis, RoleplayCodes.RELEVANCE);
        assertContainsAll(analysis, RoleplayCodes.LEARNING_STATES);
        assertContainsAll(analysis, RoleplayCodes.GAP_CODES);
        assertContainsAll(analysis, RoleplayCodes.STRATEGY_TYPES);
        assertContainsAll(generation, RoleplayCodes.RESPONSE_TYPES);
        assertContainsAll(evaluation, RoleplayCodes.FAILURE_CODES);
    }

    private static void assertContainsAll(String prompt, Set<String> codes) {
        codes.forEach(code -> assertThat(prompt).as("prompt lists %s", code).contains(code));
    }

    private AnalysisResult parsedAnalysis() {
        return factory.causeAnalysis(trace(null, null), attempts(), 1, input("울어"), null)
                .parser()
                .parse(analysisJson("PROBE_EMOTION", MG_EMOTION, "S1"));
    }

    private CandidateResponse parsedCandidate(AnalysisResult analysis) {
        return factory.responseGeneration(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        input("친구가 울고 있어."),
                        analysis,
                        CANDIDATE_ID,
                        null)
                .parser()
                .parse(candidateJson("친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?", "GUIDING_QUESTION"));
    }
}
