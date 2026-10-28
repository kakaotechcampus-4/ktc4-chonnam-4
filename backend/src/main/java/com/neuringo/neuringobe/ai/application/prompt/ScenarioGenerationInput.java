package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 시나리오 생성 한 번의 입력. 워크플로우 v3 7.1 의 scenario_generation_input.
 *
 * <p>강사 조건은 learning_goal 테이블의 값을 그대로 넘긴다. category 는 자유 문자열(최대 30자)이고, characters 는 저장된 등장인물 객체
 * 목록(예: {"id": "FRIEND", "role": "같은 반 짝꿍"})이다. 추적 식별자는 넣지 않는다.
 */
public record ScenarioGenerationInput(
        TeacherInput teacherInput, Difficulty difficulty, Revision revision) {

    public ScenarioGenerationInput {
        Objects.requireNonNull(teacherInput, "teacherInput must not be null");
        Objects.requireNonNull(difficulty, "difficulty must not be null");
    }

    public record TeacherInput(
            String learningGoal,
            String category,
            String situationType,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {

        public TeacherInput {
            if (learningGoal == null || learningGoal.isBlank()) {
                throw new IllegalArgumentException("learningGoal must not be blank");
            }
            characters = copyCharacters(characters);
            requiredElements = copyOrEmpty(requiredElements);
            forbiddenExpressions = copyOrEmpty(forbiddenExpressions);
        }
    }

    /** 난이도 결정 결과 중 시나리오에 반영할 값. scenarioLevel 만 필수다. */
    public record Difficulty(
            String scenarioLevel,
            String emotionComplexity,
            String conflictComplexity,
            String scenarioLength) {

        public Difficulty {
            RoleplayCodes.requireOneOf(
                    RoleplayCodes.SCENARIO_LEVELS, scenarioLevel, "scenarioLevel");
        }

        public static Difficulty of(String scenarioLevel) {
            return new Difficulty(scenarioLevel, null, null, null);
        }
    }

    /**
     * 재생성. 강사 수정 요청이면 previousScenario 와 teacherRequests 를 채운다. 출력이 검사에서 실패해 다시 만들 때는 이전 출력을 읽을 수
     * 없을 수 있어서 failureCodes 만 채운다(previousScenario 는 null).
     */
    public record Revision(
            GeneratedScenario previousScenario,
            List<String> teacherRequests,
            List<String> failureCodes) {

        public Revision {
            teacherRequests = copyOrEmpty(teacherRequests);
            failureCodes = copyOrEmpty(failureCodes);
            if (previousScenario == null && teacherRequests.isEmpty() && failureCodes.isEmpty()) {
                throw new IllegalArgumentException("revision must carry something to revise");
            }
        }
    }

    private static List<Map<String, Object>> copyCharacters(List<Map<String, Object>> values) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("characters must not contain empty values");
        }
        // 저장된 jsonb 는 값에 null 이 있을 수 있어 Map.copyOf 대신 순서를 지키는 복사본을 쓴다.
        return values.stream()
                .map(value -> Collections.unmodifiableMap(new LinkedHashMap<>(value)))
                .toList();
    }

    private static List<String> copyOrEmpty(List<String> values) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("list values must not be blank");
        }
        return List.copyOf(values);
    }
}
