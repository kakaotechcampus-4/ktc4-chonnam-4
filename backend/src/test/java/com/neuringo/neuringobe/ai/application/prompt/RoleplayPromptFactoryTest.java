package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.prompt.RoleplayFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans;
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
    void narratorAndCharacterEvaluationShareNativeContractAndSafeRetryFeedback() {
        for (var input : List.of(input("친구가 울고 있어."), characterInput("친구가 울고 있어."))) {
            var analysis = parsedAnalysis();
            var request =
                    factory.responseEvaluation(
                                    trace(ANALYSIS_ID, CANDIDATE_ID),
                                    attempts(),
                                    2,
                                    input,
                                    analysis,
                                    parsedCandidate(analysis),
                                    RoleplayObservationTestPlans.inputForScenario(input.scenario()),
                                    EvaluationRetry.INVALID_STRUCTURE)
                            .request();
            assertThat(request.responseSchemaVersion()).isEqualTo("evaluation-output/v9");
            assertThat(request.responseJsonSchema())
                    .contains("additionalProperties", "failure_codes");
            assertThat(request.promptVersion())
                    .isEqualTo(
                            input.scenario().aiCharacter() == null
                                    ? RoleplayPromptFactory.RESPONSE_EVALUATION_PROMPT_VERSION
                                    : RoleplayPromptFactory
                                            .CHARACTER_RESPONSE_EVALUATION_PROMPT_VERSION);
            assertThat(request.systemPrompt())
                    .contains(
                            "현재 목표 달성과 활동 전체 판정을 구분한다",
                            "retry_target, safe_to_send, critical_failure_count는 서버가 결정하므로 출력하지 않는다",
                            "error_code와 correction",
                            "통과하려고 승인하거나 판정을 임의로 PASS로 바꾸지 않는다");
            assertThat(
                            JSON.readTree(request.userPrompt())
                                    .path("retry")
                                    .path("error_code")
                                    .asText())
                    .isEqualTo("INVALID_STRUCTURE");
        }
    }

    @Test
    void causeAnalysisRequestCarriesVersionsAndTurnData() {
        PreparedPrompt<AnalysisResult> prompt =
                factory.causeAnalysis(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        input("친구가 울고 있어."),
                        null,
                        RoleplayObservationTestPlans.inputForScenario(
                                (input("친구가 울고 있어.")).scenario()));

        LlmRequest request = prompt.request();
        assertThat(request.operation()).isEqualTo(AiOperation.CAUSE_ANALYSIS);
        assertThat(request.promptVersion()).isEqualTo("roleplay-cause-analysis/v6");
        assertThat(request.responseSchemaVersion()).isEqualTo("analysis-result/v2");
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
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input("울어"),
                                null,
                                RoleplayObservationTestPlans.inputForScenario(
                                        (input("울어")).scenario()))
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
                        factory.causeAnalysis(
                                        trace(ANALYSIS_ID, null),
                                        attempts(),
                                        1,
                                        input("울어"),
                                        null,
                                        RoleplayObservationTestPlans.inputForScenario(
                                                (input("울어")).scenario()))
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
                                        candidate,
                                        RoleplayObservationTestPlans.inputForScenario(
                                                (input("울어")).scenario()))
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
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                2,
                                input("친구가 사탕을 못 가져서 화났어."),
                                new AnalysisRetry(
                                        List.of("UNSUPPORTED_CAUSE_ANALYSIS"),
                                        List.of("아동이 실제로 말한 감정"),
                                        List.of("표현하지 않은 감정 원인"),
                                        parsedAnalysis()),
                                RoleplayObservationTestPlans.inputForScenario(
                                        (input("친구가 사탕을 못 가져서 화났어.")).scenario()))
                        .request();

        JsonNode retry = JSON.readTree(request.userPrompt()).path("retry");
        assertThat(retry.path("failure_codes").get(0).asString())
                .isEqualTo("UNSUPPORTED_CAUSE_ANALYSIS");
        assertThat(retry.path("do_not_assume").get(0).asString()).isEqualTo("표현하지 않은 감정 원인");
        assertThat(retry.path("failed_analysis").path("turn_id").asString())
                .isEqualTo(TURN_ID.toString());
        assertThat(retry.path("failed_analysis").path("learning_state").asString())
                .isEqualTo(parsedAnalysis().learningState());
        assertThat(request.currentAttempt()).isEqualTo(2);
    }

    @Test
    void reanalysisRejectsFailedAnalysisFromAnotherTurn() {
        var failed =
                JSON.readValue(
                        analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")
                                .replace(TURN_ID.toString(), UUID.randomUUID().toString()),
                        AnalysisResult.class);
        var input = input("친구가 울고 있어");

        assertThatThrownBy(
                        () ->
                                factory.causeAnalysis(
                                        trace(ANALYSIS_ID, null),
                                        attempts(),
                                        2,
                                        input,
                                        new AnalysisRetry(
                                                List.of("UNSUPPORTED_CAUSE_ANALYSIS"),
                                                List.of(),
                                                List.of(),
                                                failed),
                                        RoleplayObservationTestPlans.inputForScenario(
                                                input.scenario())))
                .isInstanceOf(IllegalArgumentException.class);
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
                                candidate,
                                RoleplayObservationTestPlans.inputForScenario(
                                        (input("친구가 울고 있어.")).scenario()))
                        .request();

        JsonNode message = JSON.readTree(request.userPrompt());
        assertThat(message.path("candidate_response").path("text").asString())
                .isEqualTo("친구가 넘어져서 울고 있어. 몸은 어떤 느낌일까?");
        assertThat(
                        message.path("current_goal_assessment")
                                .path("proposed_learning_state")
                                .asString())
                .isEqualTo("PARTIAL_UNDERSTANDING");
        assertThat(request.promptVersion()).isEqualTo("roleplay-response-evaluation/v9");
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
                                        null,
                                        RoleplayObservationTestPlans.inputForScenario(
                                                (input("울어")).scenario())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                factory.responseEvaluation(
                                        trace(ANALYSIS_ID, UUID.randomUUID()),
                                        attempts(),
                                        1,
                                        input("울어"),
                                        analysis,
                                        candidate,
                                        RoleplayObservationTestPlans.inputForScenario(
                                                (input("울어")).scenario())))
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
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                input("울어"),
                                null,
                                RoleplayObservationTestPlans.inputForScenario(
                                        (input("울어")).scenario()))
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
                                parsedCandidate(parsedAnalysis()),
                                RoleplayObservationTestPlans.inputForScenario(
                                        (input("울어")).scenario()))
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

    @Test
    void analysisRepairsAMiscopiedTurnIdAndAMissingClosingSupportLevel() {
        RoleplayTurnInput character = characterInput("넘어져서 아파서 우는 거구나");
        var observations = RoleplayObservationTestPlans.inputForScenario(character.scenario());
        var parser =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                character,
                                null,
                                observations)
                        .parser();
        String expected = TURN_ID.toString();
        String miscopied = expected.substring(0, 9) + "ffff-ffff-ffff-ffffffffffff";

        AnalysisResult repaired =
                parser.parse(
                        analysisJson("CLOSE_ROLEPLAY", MG_EMOTION, "S1")
                                .replace(expected, miscopied)
                                .replace(",\"recommended_support_level\":\"S1\"", ""));

        assertThat(repaired.turnId()).isEqualTo(TURN_ID);
        assertThat(
                        RoleplayOutputParsers.withMiscopiedIdFixed(
                                "{\"analysis_id\":\""
                                        + ANALYSIS_ID.toString().substring(0, 9)
                                        + "x\"}",
                                "analysis_id",
                                ANALYSIS_ID,
                                Set.of()))
                .contains(ANALYSIS_ID.toString());
        assertThat(
                        RoleplayOutputParsers.withMiscopiedIdFixed(
                                "{\"analysis_id\":\""
                                        + ANALYSIS_ID.toString().substring(0, 9)
                                        + "x\"}",
                                "analysis_id",
                                ANALYSIS_ID,
                                Set.of()))
                .contains(ANALYSIS_ID.toString());
        assertThat(repaired.nextStrategy().recommendedSupportLevel()).isEqualTo("S1");
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")
                                                .replace(expected, UUID.randomUUID().toString())))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void characterScenarioSummarizesEvidenceSoFarPerGoal() {
        RoleplayTurnInput character = characterInput("넘어져서 아파서 우는 거구나");
        RoleplayTurnInput narrator = input("넘어져서 아파서 우는 거구나");
        var base = RoleplayObservationTestPlans.inputForScenario(character.scenario());
        var criterion = base.approvedCriteria().getFirst();
        var earlier =
                new com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput
                        .Reference(
                        UUID.randomUUID(), criterion.microGoalId(), criterion.reference());
        var later =
                new com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput
                        .Reference(
                        UUID.randomUUID(), criterion.microGoalId(), criterion.reference());
        var observations =
                RoleplayObservationTestPlans.withSyntheticObservationContexts(
                        base.approvedCriteria(),
                        List.of(
                                new com.neuringo.neuringobe.ai.application.structured.output
                                        .GoalObservationOutput.Observation(
                                        earlier,
                                        com.neuringo.neuringobe.ai.application.structured.output
                                                .GoalObservationOutput.Status.NOT_EXPRESSED),
                                new com.neuringo.neuringobe.ai.application.structured.output
                                        .GoalObservationOutput.Observation(
                                        later,
                                        com.neuringo.neuringobe.ai.application.structured.output
                                                .GoalObservationOutput.Status.IMPLICIT)));

        String characterPrompt =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                character,
                                null,
                                observations)
                        .request()
                        .userPrompt();
        String narratorPrompt =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                narrator,
                                null,
                                observations)
                        .request()
                        .userPrompt();

        assertThat(characterPrompt)
                .contains("goal_evidence_so_far")
                .contains("IMPLICIT")
                .contains("NONE")
                .contains(later.turnId().toString());
        assertThat(narratorPrompt).doesNotContain("goal_evidence_so_far");
    }

    @Test
    void characterScenarioUsesCharacterPromptsAndAllowsLinesWithoutQuestion() {
        RoleplayTurnInput character = characterInput("괜찮아?");
        RoleplayTurnInput narrator = input("괜찮아?");
        var observations = RoleplayObservationTestPlans.inputForScenario(narrator.scenario());

        var characterAnalysis =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                character,
                                null,
                                observations)
                        .request();
        var narratorAnalysis =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                narrator,
                                null,
                                observations)
                        .request();
        assertThat(characterAnalysis.systemPrompt()).contains("# 캐릭터 역할극");
        assertThat(characterAnalysis.userPrompt()).contains("\"ai_character\"");
        assertThat(narratorAnalysis.systemPrompt()).doesNotContain("# 캐릭터 역할극");
        assertThat(narratorAnalysis.userPrompt()).doesNotContain("ai_character");

        var characterGeneration =
                factory.responseGeneration(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        character,
                        parsedAnalysis(),
                        CANDIDATE_ID,
                        null);
        assertThat(characterGeneration.request().promptVersion())
                .isEqualTo(RoleplayPromptFactory.CHARACTER_RESPONSE_GENERATION_PROMPT_VERSION);
        CandidateResponse line =
                characterGeneration
                        .parser()
                        .parse(candidateJson("(훌쩍이며) 아야… 나 방금 넘어졌어.", "GUIDING_QUESTION"));
        assertThat(line.text()).doesNotContain("?");
        CandidateResponse strategyNamedLine =
                characterGeneration
                        .parser()
                        .parse(candidateJson("(훌쩍이며) 무릎이 욱신거려…", "PROBE_EMOTION"));
        assertThat(strategyNamedLine.responseType()).isEqualTo("GUIDING_QUESTION");
        assertThatThrownBy(
                        () ->
                                characterGeneration
                                        .parser()
                                        .parse(candidateJson("넘어졌어? 너도 봤어?", "GUIDING_QUESTION")))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(
                        () ->
                                factory.responseGeneration(
                                                trace(ANALYSIS_ID, null),
                                                attempts(),
                                                1,
                                                narrator,
                                                parsedAnalysis(),
                                                CANDIDATE_ID,
                                                null)
                                        .parser()
                                        .parse(
                                                candidateJson(
                                                        "(훌쩍이며) 아야… 나 방금 넘어졌어.",
                                                        "GUIDING_QUESTION")))
                .isInstanceOf(InvalidLlmOutputException.class);

        var characterEvaluationRequest =
                factory.responseEvaluation(
                                trace(ANALYSIS_ID, CANDIDATE_ID),
                                attempts(),
                                1,
                                character,
                                parsedAnalysis(),
                                parsedCandidate(parsedAnalysis()),
                                observations)
                        .request();
        String characterEvaluation = characterEvaluationRequest.systemPrompt();
        assertThat(characterEvaluationRequest.promptVersion())
                .isEqualTo(RoleplayPromptFactory.CHARACTER_RESPONSE_EVALUATION_PROMPT_VERSION);
        assertThat(characterEvaluation)
                .contains("캐릭터 대사는 질문이 아니어도 된다")
                .doesNotContain("MISSING_QUESTION");
        assertContainsAll(
                characterEvaluation,
                RoleplayCodes.FAILURE_CODES.stream()
                        .filter(code -> !code.equals("MISSING_QUESTION"))
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void mascotHintIsRequestedOnlyWhenTheServerRuleAsksForIt() {
        RoleplayTurnInput character = characterInput("몰라");
        AnalysisResult dontKnow =
                factory.causeAnalysis(
                                trace(ANALYSIS_ID, null),
                                attempts(),
                                1,
                                character,
                                null,
                                RoleplayObservationTestPlans.inputForScenario(character.scenario()))
                        .parser()
                        .parse(
                                analysisJson("PROBE_EMOTION", MG_EMOTION, "S1")
                                        .replace("ANSWER_ATTEMPT", "DONT_KNOW")
                                        .replace("PARTIAL_UNDERSTANDING", "HELP_NEEDED"));
        var withHint =
                factory.responseGeneration(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        character,
                        dontKnow,
                        CANDIDATE_ID,
                        null);
        assertThat(withHint.request().userPrompt())
                .contains("\"mascot_hint\"")
                .contains("HELP_REQUEST");

        String line = candidateJson("(훌쩍이며) 흐윽…", "GUIDING_QUESTION");
        String hinted =
                line.replace(
                        "\"previous_failed_candidate_ids\":[]",
                        "\"previous_failed_candidate_ids\":[],\"mascot_hint\":\"친구가 무릎을 잡고 있네. 어디가 아픈지 물어볼까?\"");
        assertThat(withHint.parser().parse(hinted).mascotHint()).startsWith("친구가 무릎을");
        assertThatThrownBy(() -> withHint.parser().parse(line))
                .isInstanceOf(InvalidLlmOutputException.class);
        // "null" 문자열은 힌트가 없는 것으로 보고, 지시문처럼 쓴 힌트는 막는다.
        assertThatThrownBy(
                        () ->
                                withHint.parser()
                                        .parse(
                                                line.replace(
                                                        "\"previous_failed_candidate_ids\":[]",
                                                        "\"previous_failed_candidate_ids\":[],\"mascot_hint\":\"null\"")))
                .isInstanceOf(InvalidLlmOutputException.class);
        for (String instruction :
                List.of("친구가 무릎을 잡고 있다는 걸 보고, 넘어졌던 일을 다시 말해보게 하기", "캐릭터가 무릎이 아프다고 말하게 해줘.")) {
            assertThatThrownBy(
                            () ->
                                    withHint.parser()
                                            .parse(
                                                    line.replace(
                                                            "\"previous_failed_candidate_ids\":[]",
                                                            "\"previous_failed_candidate_ids\":[],\"mascot_hint\":\""
                                                                    + instruction
                                                                    + "\"")))
                    .isInstanceOf(InvalidLlmOutputException.class);
        }

        var withoutHint =
                factory.responseGeneration(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        character,
                        parsedAnalysis(),
                        CANDIDATE_ID,
                        null);
        assertThat(withoutHint.request().userPrompt()).doesNotContain("mascot_hint");
        assertThatThrownBy(() -> withoutHint.parser().parse(hinted))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    private static void assertContainsAll(String prompt, Set<String> codes) {
        codes.forEach(code -> assertThat(prompt).as("prompt lists %s", code).contains(code));
    }

    private AnalysisResult parsedAnalysis() {
        return factory.causeAnalysis(
                        trace(ANALYSIS_ID, null),
                        attempts(),
                        1,
                        input("울어"),
                        null,
                        RoleplayObservationTestPlans.inputForScenario((input("울어")).scenario()))
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
