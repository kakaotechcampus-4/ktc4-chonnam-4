package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ScenarioPromptFactoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ScenarioPromptFactory factory =
            new ScenarioPromptFactory(JSON, "CONTENT-MVP-001");

    @Test
    void requestCarriesVersionsAndTeacherInput() {
        LlmRequest request = factory.generation(trace(), attempts(), 1, input("L1")).request();

        assertThat(request.operation()).isEqualTo(AiOperation.SCENARIO_GENERATION);
        assertThat(request.promptVersion()).isEqualTo("scenario-generation/v1");
        assertThat(request.responseSchemaVersion()).isEqualTo("generated-scenario/v1");
        assertThat(request.policyVersion()).isEqualTo("CONTENT-MVP-001");
        assertThat(request.systemPrompt()).contains("시나리오 생성 에이전트");

        JsonNode message = JSON.readTree(request.userPrompt());
        assertThat(message.path("teacher_input").path("learning_goal").asString())
                .isEqualTo("친구의 감정을 이해하고 공감하기");
        assertThat(message.path("teacher_input").path("forbidden_expressions").get(0).asString())
                .isEqualTo("싸움");
        assertThat(message.path("difficulty").path("scenario_level").asString()).isEqualTo("L1");
        assertThat(message.path("difficulty").has("emotion_complexity")).isFalse();
        assertThat(message.has("revision")).isFalse();
    }

    @Test
    void teacherInputTakesStoredLearningGoalValuesAsIs() {
        Map<String, Object> friend = new LinkedHashMap<>();
        friend.put("id", "FRIEND");
        friend.put("role", "같은 반 친구");
        friend.put("note", null);
        ScenarioGenerationInput.TeacherInput teacher =
                new ScenarioGenerationInput.TeacherInput(
                        "친구의 감정을 이해하고 공감하기",
                        "친구 마음에 공감하기",
                        "PLAYGROUND",
                        List.of(friend),
                        null,
                        null);

        JsonNode message =
                JSON.readTree(
                                factory.generation(
                                                trace(),
                                                attempts(),
                                                1,
                                                new ScenarioGenerationInput(
                                                        teacher,
                                                        ScenarioGenerationInput.Difficulty.of("L1"),
                                                        null))
                                        .request()
                                        .userPrompt())
                        .path("teacher_input");

        assertThat(message.path("category").asString()).isEqualTo("친구 마음에 공감하기");
        assertThat(message.path("situation_type").asString()).isEqualTo("PLAYGROUND");
        JsonNode character = message.path("characters").get(0);
        assertThat(character.path("id").asString()).isEqualTo("FRIEND");
        assertThat(character.path("role").asString()).isEqualTo("같은 반 친구");
    }

    @Test
    void teacherInputRejectsEmptyCharacter() {
        List<Map<String, Object>> characters = new ArrayList<>();
        characters.add(Map.of());

        assertThatThrownBy(
                        () ->
                                new ScenarioGenerationInput.TeacherInput(
                                        "목표", null, null, characters, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promptNeverCarriesTraceIdentifiers() {
        AiTraceContext trace = trace();
        String user = factory.generation(trace, attempts(), 1, input("L1")).request().userPrompt();

        assertThat(user).doesNotContain(trace.requestId().toString());
        assertThat(user).doesNotContain(trace.activityId().toString());
        assertThat(user).doesNotContain(trace.scenarioId().toString());
    }

    @Test
    void revisionCarriesPreviousScenarioAndRequests() {
        GeneratedScenario previous = parse("L1", scenario(json -> {}));
        ScenarioGenerationInput input =
                new ScenarioGenerationInput(
                        teacher(),
                        ScenarioGenerationInput.Difficulty.of("L1"),
                        new ScenarioGenerationInput.Revision(
                                previous, List.of("장소를 교실로 바꿔 주세요"), List.of("TOO_LONG")));

        JsonNode revision =
                JSON.readTree(
                                factory.generation(trace(), attempts(), 2, input)
                                        .request()
                                        .userPrompt())
                        .path("revision");

        assertThat(revision.path("previous_scenario").path("scenario_facts")).hasSize(3);
        assertThat(
                        revision.path("previous_scenario")
                                .path("micro_goals")
                                .get(0)
                                .path("id")
                                .asString())
                .isEqualTo("MG-01");
        assertThat(revision.path("teacher_requests").get(0).asString()).isEqualTo("장소를 교실로 바꿔 주세요");
        assertThat(revision.path("failure_codes").get(0).asString()).isEqualTo("TOO_LONG");
    }

    @Test
    void acceptsValidScenarioInsideCodeFence() {
        String content = "```json\n" + JSON.writeValueAsString(scenario(json -> {})) + "\n```";

        GeneratedScenario parsed =
                factory.generation(trace(), attempts(), 1, input("L1")).parser().parse(content);

        assertThat(parsed.microGoals()).hasSize(3);
        assertThat(parsed.startingQuestions()).containsOnlyKeys("S0", "S1", "S2");
    }

    @Test
    void rejectsLevelOtherThanRequested() {
        assertFailure("L2", json -> {}, "LEVEL_MISMATCH");
    }

    @Test
    void enforcesFactAndGoalCountsPerLevel() {
        assertFailure(
                "L1",
                json -> facts(json).addAll(List.of("친구가 무릎을 잡았다.", "친구가 자리에 앉았다.")),
                "FACT_COUNT_OUT_OF_RANGE");
        assertFailure("L1", json -> facts(json).remove(2), "FACT_COUNT_OUT_OF_RANGE");
        assertFailure("L1", json -> goals(json).remove(2), "MICRO_GOAL_COUNT_OUT_OF_RANGE");
        assertFailure(
                "L1",
                json -> {
                    facts(json).remove(2);
                    facts(json).add("친구가 넘어졌다.");
                },
                "DUPLICATE_FACT");
    }

    @Test
    void acceptsL3WithFourGoalsAndSixFacts() {
        GeneratedScenario parsed =
                parse(
                        "L3",
                        scenario(
                                json -> {
                                    json.put("scenario_level", "L3");
                                    facts(json)
                                            .addAll(
                                                    List.of(
                                                            "친구가 무릎을 잡았다.",
                                                            "짝꿍이 친구 옆에 왔다.",
                                                            "짝꿍이 친구를 쳐다보았다."));
                                    goals(json).add(goal("MG-04", "친구에게 해 줄 말을 생각한다."));
                                }));

        assertThat(parsed.microGoals()).hasSize(4);
    }

    @Test
    void microGoalIdsMustBeSequential() {
        assertFailure("L1", json -> goals(json).get(1).put("id", "MG-03"), "MICRO_GOAL_ID_INVALID");
        assertFailure(
                "L1", json -> goals(json).get(0).put("id", "goal-1"), "MICRO_GOAL_ID_INVALID");
    }

    @Test
    void startingQuestionsAreS0ToS2WithOneQuestionEach() {
        assertFailure(
                "L1",
                json -> questions(json).put("S3", "친구는 넘어져서 아팠을 거야."),
                "STARTING_QUESTIONS_INVALID");
        assertFailure("L1", json -> questions(json).remove("S2"), "STARTING_QUESTIONS_INVALID");
        assertFailure(
                "L1",
                json -> questions(json).put("S0", "무슨 일이 있었을까? 친구는 어땠어?"),
                "QUESTION_FORMAT_INVALID");
        assertFailure(
                "L1", json -> questions(json).put("S1", "친구가 울고 있어."), "QUESTION_FORMAT_INVALID");
        assertFailure("L1", json -> questions(json).put("S0", "가".repeat(80) + "?"), "TOO_LONG");
    }

    @Test
    void charactersStartWithLearnerAndAiRoleIsNarratorOrCharacter() {
        assertFailure("L1", json -> characters(json).remove(0), "CHARACTERS_INVALID");
        assertFailure("L1", json -> json.put("ai_role", "LEARNER"), "AI_ROLE_INVALID");
        assertFailure("L1", json -> json.put("ai_role", "TEACHER"), "AI_ROLE_INVALID");

        assertFailure("L1", json -> json.put("ai_role", "FRIEND"), "CHARACTERS_INVALID");

        GeneratedScenario asCharacter = parse("L1", scenario(ScenarioPromptFactoryTest::asFriend));
        assertThat(asCharacter.aiRole()).isEqualTo("FRIEND");
        assertThat(asCharacter.characters().get(1).speechStyle()).isEqualTo("반말, 짧은 문장");
    }

    @Test
    void characterOpeningLinesNeedNotAskButAskAtMostOnce() {
        GeneratedScenario parsed = parse("L1", scenario(ScenarioPromptFactoryTest::asFriend));
        assertThat(parsed.startingQuestions().get("S0")).isEqualTo("(훌쩍이며) 흐윽…");

        assertFailure(
                "L1",
                json -> {
                    asFriend(json);
                    questions(json).put("S1", "아야… 넘어졌어? 너 봤어?");
                },
                "QUESTION_FORMAT_INVALID");
        assertFailure(
                "L1", json -> questions(json).put("S0", "(훌쩍이며) 흐윽…"), "QUESTION_FORMAT_INVALID");
    }

    @Test
    void approvedCharacterScenarioCarriesPersonaIntoRoleplay() {
        GeneratedScenario parsed = parse("L1", scenario(ScenarioPromptFactoryTest::asFriend));
        Map<String, UUID> ids = new LinkedHashMap<>();
        parsed.microGoals().forEach(goal -> ids.put(goal.id(), UUID.randomUUID()));

        RoleplayTurnInput.Scenario roleplay =
                ScenarioPromptFactory.toRoleplayScenario(parsed, "친구의 감정을 이해하고 공감하기", ids);

        assertThat(roleplay.aiCharacter().id()).isEqualTo("FRIEND");
        assertThat(roleplay.aiCharacter().personality()).isEqualTo("소심하고 잘 우는 편");
    }

    @Test
    void rejectsCharacterIdsInsideSentences() {
        assertFailure(
                "L1",
                json -> questions(json).put("S1", "FRIEND가 미끄럼틀 앞에서 울고 있어. 무슨 일이 있었을까?"),
                "CHARACTER_ID_IN_TEXT");
        assertFailure(
                "L1", json -> facts(json).set(0, "LEARNER가 친구를 보았다."), "CHARACTER_ID_IN_TEXT");
    }

    @Test
    void rejectsTeacherForbiddenExpressionEvenWithSpacing() {
        assertFailure(
                "L1", json -> facts(json).set(0, "두 친구가 싸 움을 했다."), "FORBIDDEN_EXPRESSION_USED");
    }

    @Test
    void rejectsUnsafeTermsButAllowsThemInProhibitedInferences() {
        assertFailure("L1", json -> facts(json).set(0, "친구가 동생을 때렸다."), "UNSAFE_CONTENT");
        assertFailure(
                "L1",
                json -> goals(json).get(0).put("description", "ADHD 친구의 마음을 안다."),
                "UNSAFE_CONTENT");

        GeneratedScenario parsed =
                parse(
                        "L1",
                        scenario(json -> inferences(json).add("누가 때렸다는 등 시나리오에 없는 사건을 지어내지 않는다.")));
        assertThat(parsed.prohibitedInferences()).hasSize(2);
    }

    @Test
    void rejectsNumberPatternsAsPersonalInformation() {
        assertFailure(
                "L1",
                json -> json.put("summary", "친구 번호는 010-1234-5678 이다."),
                "PERSONAL_INFORMATION");
        assertFailure(
                "L1",
                json -> inferences(json).set(0, "123456 같은 번호를 묻지 않는다."),
                "PERSONAL_INFORMATION");
    }

    @Test
    void rejectsMissingFieldsAndLongSentences() {
        assertFailure("L1", json -> json.remove("starting_questions"), "required structure");
        assertFailure(
                "L1",
                json -> goals(json).get(0).remove("achievement_example"),
                "required structure");
        assertFailure("L1", json -> facts(json).set(0, "가".repeat(61)), "TOO_LONG");
    }

    @Test
    void convertsApprovedScenarioToRoleplayInput() {
        GeneratedScenario parsed = parse("L1", scenario(json -> {}));
        Map<String, UUID> ids =
                Map.of(
                        "MG-01",
                        UUID.randomUUID(),
                        "MG-02",
                        UUID.randomUUID(),
                        "MG-03",
                        UUID.randomUUID());

        RoleplayTurnInput.Scenario roleplay =
                ScenarioPromptFactory.toRoleplayScenario(parsed, "친구의 감정을 이해하고 공감하기", ids);

        assertThat(roleplay.scenarioLevel()).isEqualTo("L1");
        assertThat(roleplay.scenarioFacts()).isEqualTo(parsed.scenarioFacts());
        assertThat(roleplay.microGoals()).hasSize(3);
        assertThat(roleplay.microGoals().get(1).id()).isEqualTo(ids.get("MG-02"));
        assertThat(roleplay.microGoals()).allMatch(goal -> goal.status().equals("NOT_OBSERVED"));
        assertThatThrownBy(
                        () ->
                                ScenarioPromptFactory.toRoleplayScenario(
                                        parsed, "목표", Map.of("MG-01", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- fixtures

    private void assertFailure(String level, Consumer<Map<String, Object>> edit, String reason) {
        assertThatThrownBy(() -> parse(level, scenario(edit)))
                .isInstanceOf(InvalidLlmOutputException.class)
                .hasMessageContaining(reason);
    }

    private GeneratedScenario parse(String level, Map<String, Object> json) {
        return factory.generation(trace(), attempts(), 1, input(level))
                .parser()
                .parse(JSON.writeValueAsString(json));
    }

    private static ScenarioGenerationInput input(String level) {
        return new ScenarioGenerationInput(
                teacher(), ScenarioGenerationInput.Difficulty.of(level), null);
    }

    private static ScenarioGenerationInput.TeacherInput teacher() {
        return new ScenarioGenerationInput.TeacherInput(
                "친구의 감정을 이해하고 공감하기",
                "EMOTION_RECOGNITION",
                "놀이터",
                List.of(Map.of("id", "FRIEND", "role", "같은 반 친구")),
                List.of("친구의 표정을 보는 장면"),
                List.of("싸움"));
    }

    private static AiTraceContext trace() {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                1,
                null,
                null,
                null,
                null);
    }

    private static AiAttemptContext attempts() {
        return new AiAttemptContext(0, 1, 0);
    }

    /** L1 기준 올바른 출력. edit 로 한 곳만 바꿔 실패를 만든다. */
    private static Map<String, Object> scenario(Consumer<Map<String, Object>> edit) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", "넘어져서 우는 친구");
        json.put("summary", "놀이터에서 친구가 미끄럼틀 앞에서 넘어져 울었다.");
        json.put("scenario_level", "L1");
        json.put("ai_role", "NARRATOR");
        json.put(
                "characters",
                new ArrayList<>(
                        List.of(
                                new LinkedHashMap<>(Map.of("id", "LEARNER", "role", "학습자")),
                                new LinkedHashMap<>(Map.of("id", "FRIEND", "role", "넘어진 친구")))));
        json.put(
                "scenario_facts",
                new ArrayList<>(List.of("친구가 미끄럼틀로 뛰어갔다.", "친구가 넘어졌다.", "친구가 울었다.")));
        json.put("prohibited_inferences", new ArrayList<>(List.of("친구가 일부러 넘어졌다고 단정하지 않는다.")));
        json.put(
                "micro_goals",
                new ArrayList<>(
                        List.of(
                                goal("MG-01", "친구가 넘어졌다는 사건을 파악한다."),
                                goal("MG-02", "친구가 아프거나 속상할 수 있음을 파악한다."),
                                goal("MG-03", "감정과 넘어진 사건을 연결한다."))));
        json.put(
                "starting_questions",
                new LinkedHashMap<>(
                        Map.of(
                                "S0", "아까 놀이터에서 무슨 일이 있었는지 봤어?",
                                "S1", "친구가 미끄럼틀 앞에서 울고 있어. 무슨 일이 있었을까?",
                                "S2", "친구가 넘어졌을까, 아니면 장난감을 잃어버렸을까?")));
        edit.accept(json);
        return json;
    }

    private static Map<String, Object> goal(String id, String description) {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("id", id);
        goal.put("description", description);
        goal.put("required_evidence", new ArrayList<>(List.of("관련된 내용을 말한다.")));
        goal.put("achievement_example", "친구가 넘어졌어.");
        return goal;
    }

    @SuppressWarnings("unchecked")
    private static List<String> facts(Map<String, Object> json) {
        return (List<String>) json.get("scenario_facts");
    }

    @SuppressWarnings("unchecked")
    private static List<String> inferences(Map<String, Object> json) {
        return (List<String>) json.get("prohibited_inferences");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> goals(Map<String, Object> json) {
        return (List<Map<String, Object>>) json.get("micro_goals");
    }

    /** AI 가 넘어진 친구를 1인칭으로 연기하는 시나리오로 바꾼다. */
    private static void asFriend(Map<String, Object> json) {
        json.put("ai_role", "FRIEND");
        characters(json).get(1).put("personality", "소심하고 잘 우는 편");
        characters(json).get(1).put("speech_style", "반말, 짧은 문장");
        questions(json).put("S0", "(훌쩍이며) 흐윽…");
        questions(json).put("S1", "(무릎을 감싸 쥐며) 아야… 나 방금 미끄럼틀 앞에서…");
        questions(json).put("S2", "(울먹이며) 넘어진 건지… 미끄러진 건지… 정신이 없어.");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> characters(Map<String, Object> json) {
        return (List<Map<String, Object>>) json.get("characters");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> questions(Map<String, Object> json) {
        return (Map<String, String>) json.get("starting_questions");
    }
}
