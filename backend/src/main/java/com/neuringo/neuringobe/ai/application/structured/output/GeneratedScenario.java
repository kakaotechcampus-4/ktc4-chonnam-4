package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 시나리오 생성 프롬프트 v1 의 출력(scenario-generation/v1). 강사 검토 전 상태다.
 *
 * <p>여기서는 모양만 확인한다. 난이도별 개수, 금지 표현, 길이 같은 계약 검사는 ScenarioOutputParser 가 한다. micro goal id 는 MG-01
 * 형태의 모델 키이고, 저장할 때 UUID 로 바꾼다.
 */
public record GeneratedScenario(
        String title,
        String summary,
        @JsonProperty("scenario_level") String scenarioLevel,
        @JsonProperty("ai_role") String aiRole,
        List<Character> characters,
        @JsonProperty("scenario_facts") List<String> scenarioFacts,
        @JsonProperty("prohibited_inferences") List<String> prohibitedInferences,
        @JsonProperty("micro_goals") List<MicroGoal> microGoals,
        @JsonProperty("starting_questions") Map<String, String> startingQuestions) {

    public GeneratedScenario {
        requireText(title, "title");
        requireText(summary, "summary");
        requireText(scenarioLevel, "scenarioLevel");
        requireText(aiRole, "aiRole");
        characters = requireList(characters, "characters");
        scenarioFacts = requireList(scenarioFacts, "scenarioFacts");
        prohibitedInferences =
                prohibitedInferences == null ? List.of() : List.copyOf(prohibitedInferences);
        microGoals = requireList(microGoals, "microGoals");
        startingQuestions =
                Map.copyOf(
                        Objects.requireNonNull(
                                startingQuestions, "startingQuestions must not be null"));
    }

    /** personality·speech_style 은 AI 가 연기하는 인물(ai_role)에만 있다. 학습자(LEARNER)에는 없다. */
    public record Character(
            String id,
            String role,
            String personality,
            @JsonProperty("speech_style") String speechStyle) {

        public Character(String id, String role) {
            this(id, role, null, null);
        }

        public Character {
            requireText(id, "character.id");
            requireText(role, "character.role");
        }
    }

    public record MicroGoal(
            String id,
            String description,
            @JsonProperty("required_evidence") List<String> requiredEvidence,
            @JsonProperty("achievement_example") String achievementExample) {

        public MicroGoal {
            requireText(id, "microGoal.id");
            requireText(description, "microGoal.description");
            requiredEvidence = requireList(requiredEvidence, "microGoal.requiredEvidence");
            requireText(achievementExample, "microGoal.achievementExample");
        }
    }

    private static <T> List<T> requireList(List<T> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(values);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
