package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * 역할극 시나리오 생성 프롬프트(SCN-01·02·10)를 만든다. 워크플로우 v3 7장 기준.
 *
 * <p>시스템 프롬프트는 classpath 의 prompts/scenario/v1/scenario-generation.md 이고, 사용자 메시지는 입력 JSON 하나다. 추적
 * 식별자는 프롬프트에 넣지 않는다.
 *
 * <p>재시도 횟수와 실패 시 기본 시나리오 제시는 호출하는 쪽의 책임이다. 이 클래스는 요청과, 그 요청에 맞는 출력인지 검사하는 parser 만 만든다.
 */
public final class ScenarioPromptFactory {

    public static final String SCENARIO_GENERATION_PROMPT_VERSION = "scenario-generation/v1";
    public static final String SCENARIO_SCHEMA_VERSION = "generated-scenario/v1";

    private final ObjectMapper objectMapper;
    private final String policyVersion;
    private final String systemPrompt;

    /**
     * @param policyVersion 콘텐츠 안전 정책 버전. 정해지지 않았으면 null.
     */
    public ScenarioPromptFactory(ObjectMapper objectMapper, String policyVersion) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.policyVersion = policyVersion;
        this.systemPrompt = PromptTemplates.load("scenario/v1/scenario-generation.md");
    }

    public PreparedPrompt<GeneratedScenario> generation(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            ScenarioGenerationInput input) {
        Objects.requireNonNull(trace, "trace must not be null");
        Objects.requireNonNull(attempts, "attempts must not be null");
        Objects.requireNonNull(input, "input must not be null");

        LlmRequest request =
                new LlmRequest(
                        trace,
                        attempts,
                        AiOperation.SCENARIO_GENERATION,
                        currentAttempt,
                        systemPrompt,
                        objectMapper.writeValueAsString(message(input)),
                        SCENARIO_GENERATION_PROMPT_VERSION,
                        SCENARIO_SCHEMA_VERSION,
                        policyVersion);
        return new PreparedPrompt<>(request, ScenarioOutputParser.create(objectMapper, input));
    }

    /**
     * 승인된 시나리오를 역할극 한 턴의 입력으로 바꾼다. microGoalIds 는 MG-01 같은 모델 키를 저장된 UUID 로 바꾼다. 모든 목표는 아직 관찰
     * 전(NOT_OBSERVED)이다.
     */
    public static RoleplayTurnInput.Scenario toRoleplayScenario(
            GeneratedScenario scenario, String learningGoal, Map<String, UUID> microGoalIds) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(microGoalIds, "microGoalIds must not be null");
        List<RoleplayTurnInput.MicroGoal> goals =
                scenario.microGoals().stream()
                        .map(
                                goal -> {
                                    UUID id = microGoalIds.get(goal.id());
                                    if (id == null) {
                                        throw new IllegalArgumentException(
                                                "no id for micro goal " + goal.id());
                                    }
                                    return new RoleplayTurnInput.MicroGoal(
                                            id,
                                            goal.description(),
                                            goal.requiredEvidence(),
                                            "NOT_OBSERVED");
                                })
                        .toList();
        RoleplayTurnInput.AiCharacter aiCharacter =
                scenario.characters().stream()
                        .filter(character -> character.id().equals(scenario.aiRole()))
                        .findFirst()
                        .map(
                                character ->
                                        new RoleplayTurnInput.AiCharacter(
                                                character.id(),
                                                character.role(),
                                                character.personality(),
                                                character.speechStyle()))
                        .orElse(null);
        return new RoleplayTurnInput.Scenario(
                learningGoal,
                scenario.scenarioLevel(),
                scenario.scenarioFacts(),
                scenario.prohibitedInferences(),
                goals,
                aiCharacter);
    }

    private Map<String, Object> message(ScenarioGenerationInput input) {
        ScenarioGenerationInput.TeacherInput teacher = input.teacherInput();
        Map<String, Object> teacherSection = new LinkedHashMap<>();
        teacherSection.put("learning_goal", teacher.learningGoal());
        teacherSection.put("category", teacher.category());
        teacherSection.put("situation_type", teacher.situationType());
        teacherSection.put("characters", teacher.characters());
        teacherSection.put("required_elements", teacher.requiredElements());
        teacherSection.put("forbidden_expressions", teacher.forbiddenExpressions());

        ScenarioGenerationInput.Difficulty difficulty = input.difficulty();
        Map<String, Object> difficultySection = new LinkedHashMap<>();
        difficultySection.put("scenario_level", difficulty.scenarioLevel());
        putIfPresent(difficultySection, "emotion_complexity", difficulty.emotionComplexity());
        putIfPresent(difficultySection, "conflict_complexity", difficulty.conflictComplexity());
        putIfPresent(difficultySection, "scenario_length", difficulty.scenarioLength());

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("teacher_input", teacherSection);
        message.put("difficulty", difficultySection);
        if (input.revision() != null) {
            Map<String, Object> revision = new LinkedHashMap<>();
            if (input.revision().previousScenario() != null) {
                revision.put("previous_scenario", input.revision().previousScenario());
            }
            revision.put("teacher_requests", input.revision().teacherRequests());
            revision.put("failure_codes", input.revision().failureCodes());
            message.put("revision", revision);
        }
        return message;
    }

    private static void putIfPresent(Map<String, Object> section, String key, String value) {
        if (value != null && !value.isBlank()) section.put(key, value);
    }
}
