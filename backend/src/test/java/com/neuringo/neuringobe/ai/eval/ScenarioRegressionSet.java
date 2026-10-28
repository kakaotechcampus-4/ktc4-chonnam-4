package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시나리오 생성 품질 평가 세트(src/test/resources/eval/scenario-generation-v1.json). 강사 입력과 난이도를 고정해 둔다.
 *
 * <p>expect 는 계약 검사(ScenarioOutputParser)가 못 보는 내용 기준이다. 낱말 포함 여부로만 보는 거친 기준이라, 결과는 사람이
 * scenarios.md 로 다시 본다.
 */
record ScenarioRegressionSet(String setId, String description, List<Case> cases) {

    static final String DEFAULT_RESOURCE = "eval/scenario-generation-v1.json";

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    ScenarioRegressionSet {
        Objects.requireNonNull(setId, "setId must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
    }

    static ScenarioRegressionSet load(String resource) {
        try (InputStream in =
                ScenarioRegressionSet.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("scenario set not found: " + resource);
            }
            return JSON.readValue(in, ScenarioRegressionSet.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Case(
            String id,
            String purpose,
            ScenarioGenerationInput.TeacherInput teacherInput,
            ScenarioGenerationInput.Difficulty difficulty,
            Expectation expect) {

        Expectation expectation() {
            return expect == null ? new Expectation(null, null, null, null) : expect;
        }

        /** 앞선 시도가 계약 검사에서 실패했으면 그 코드를 붙여 다시 만든다. */
        ScenarioGenerationInput input(List<String> failureCodes) {
            ScenarioGenerationInput.Revision revision =
                    failureCodes.isEmpty()
                            ? null
                            : new ScenarioGenerationInput.Revision(null, List.of(), failureCodes);
            return new ScenarioGenerationInput(teacherInput, difficulty, revision);
        }
    }

    /**
     * @param mentionsAny 묶음마다 낱말 하나 이상이 시나리오 어딘가에 있어야 한다(상황 반영)
     * @param goalsMentionAny 묶음마다 낱말 하나 이상이 마이크로 목표(설명·달성 조건)에 있어야 한다(필수 요소 = 아동 행동 반영)
     * @param mustNotMention 시나리오 어디에도 없어야 한다(지시문 주입을 따르지 않았는지)
     * @param lastGoalMentionsAny 마지막 마이크로 목표 설명에 하나 이상 있어야 한다(분류별 마지막 목표)
     */
    record Expectation(
            List<List<String>> mentionsAny,
            List<List<String>> goalsMentionAny,
            List<String> mustNotMention,
            List<String> lastGoalMentionsAny) {

        List<Check> check(GeneratedScenario scenario) {
            String all = text(scenario);
            List<Check> checks = new ArrayList<>();
            if (mentionsAny != null) {
                for (List<String> group : mentionsAny) {
                    checks.add(
                            new Check(
                                    "mentions_any " + group,
                                    group.stream().anyMatch(all::contains)));
                }
            }
            if (goalsMentionAny != null) {
                String goals = goalText(scenario);
                for (List<String> group : goalsMentionAny) {
                    checks.add(
                            new Check(
                                    "goals_mention_any " + group,
                                    group.stream().anyMatch(goals::contains)));
                }
            }
            if (mustNotMention != null) {
                for (String term : mustNotMention) {
                    checks.add(new Check("must_not_mention " + term, !all.contains(term)));
                }
            }
            if (lastGoalMentionsAny != null) {
                String last = scenario.microGoals().getLast().description();
                checks.add(
                        new Check(
                                "last_goal_mentions_any " + lastGoalMentionsAny,
                                lastGoalMentionsAny.stream().anyMatch(last::contains)));
            }
            return checks;
        }

        private static String goalText(GeneratedScenario scenario) {
            StringBuilder text = new StringBuilder();
            for (GeneratedScenario.MicroGoal goal : scenario.microGoals()) {
                text.append(goal.description()).append('\n');
                goal.requiredEvidence().forEach(e -> text.append(e).append('\n'));
            }
            return text.toString();
        }

        private static String text(GeneratedScenario scenario) {
            StringBuilder text = new StringBuilder();
            text.append(scenario.title()).append('\n').append(scenario.summary()).append('\n');
            scenario.characters().forEach(c -> text.append(c.role()).append('\n'));
            scenario.scenarioFacts().forEach(f -> text.append(f).append('\n'));
            scenario.prohibitedInferences().forEach(f -> text.append(f).append('\n'));
            for (GeneratedScenario.MicroGoal goal : scenario.microGoals()) {
                text.append(goal.description()).append('\n');
                goal.requiredEvidence().forEach(e -> text.append(e).append('\n'));
                text.append(goal.achievementExample()).append('\n');
            }
            scenario.startingQuestions().values().forEach(q -> text.append(q).append('\n'));
            return text.toString();
        }
    }

    record Check(String name, boolean passed) {}
}
