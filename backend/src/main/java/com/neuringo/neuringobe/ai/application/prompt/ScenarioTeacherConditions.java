package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 생성한 시나리오가 강사 조건(등장인물·피할 표현)을 지켰는지 본다. 서버의 시나리오 생성(ScenarioGenerationService)·저장된 후보 검사와 평가
 * 도구(scenarioEval)가 이 한 곳의 규칙을 같이 쓴다.
 *
 * <p>어기면 실패 코드를 돌려준다. 서버는 그 코드를 revision.failure_codes 로 붙여 같은 요청 안에서 다시 만든다. 등장인물은 강사가 적은 role
 * 글자(공백만 정리)가 출력 characters 의 role 과 그대로 같아야 한다. 강사가 이름("민수")을 적었으면 출력 role 도 "민수"다.
 */
public final class ScenarioTeacherConditions {

    /** 출력 등장인물(학습자 제외)의 role 이 강사가 적은 role 과 다르다. */
    public static final String CHARACTER_ROLE_MISMATCH = "CHARACTER_ROLE_MISMATCH";

    /** 강사가 피할 표현으로 적은 말이 아동·강사에게 보이는 글에 들어갔다. */
    public static final String FORBIDDEN_EXPRESSION_USED = "FORBIDDEN_EXPRESSION_USED";

    private static final String LEARNER = "LEARNER";
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private ScenarioTeacherConditions() {}

    /** 지켰으면 빈 값, 어겼으면 실패 코드. */
    public static Optional<String> violation(
            ScenarioGenerationInput.TeacherInput input, GeneratedScenario scenario) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        List<String> expectedRoles =
                input.characters().stream()
                        .map(character -> normalize(Objects.toString(character.get("role"), "")))
                        .sorted()
                        .toList();
        List<String> actualRoles =
                scenario.characters().stream()
                        .filter(character -> !LEARNER.equals(character.id()))
                        .map(character -> normalize(character.role()))
                        .sorted()
                        .toList();
        if (!expectedRoles.isEmpty() && !expectedRoles.equals(actualRoles))
            return Optional.of(CHARACTER_ROLE_MISMATCH);

        List<String> forbidden =
                input.forbiddenExpressions().stream()
                        .map(ScenarioTeacherConditions::compact)
                        .filter(term -> !term.isEmpty())
                        .toList();
        if (texts(scenario).stream()
                .map(ScenarioTeacherConditions::compact)
                .anyMatch(text -> forbidden.stream().anyMatch(text::contains)))
            return Optional.of(FORBIDDEN_EXPRESSION_USED);
        return Optional.empty();
    }

    private static List<String> texts(GeneratedScenario scenario) {
        var texts = new ArrayList<>(List.of(scenario.title(), scenario.summary()));
        scenario.characters().forEach(character -> texts.add(character.role()));
        texts.addAll(scenario.scenarioFacts());
        texts.addAll(scenario.startingQuestions().values());
        scenario.microGoals()
                .forEach(
                        goal -> {
                            texts.add(goal.description());
                            texts.addAll(goal.requiredEvidence());
                            texts.add(goal.achievementExample());
                        });
        return texts;
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    private static String compact(String text) {
        return WHITESPACE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
