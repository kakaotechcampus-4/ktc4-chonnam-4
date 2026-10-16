package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 품질 회귀 세트(src/test/resources/eval/*.json). 각 턴은 상태와 최근 대화를 미리 정해 두어, 앞 턴의 모델 답변이 달라도 입력이 바뀌지 않는다.
 *
 * <p>utterance 는 아이가 실제로 말한 문장(음성 왕복 측정에 쓴다), canonical_utterance 는 입력 처리(개인정보 가림)를 통과한 뒤 LLM 에
 * 들어가는 문장이다. 없으면 utterance 를 그대로 쓴다.
 */
record RegressionSet(
        String setId, String description, Scenario scenario, Policy policy, List<Case> cases) {

    static final String DEFAULT_RESOURCE = "eval/roleplay-regression-v1.json";

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    RegressionSet {
        Objects.requireNonNull(setId, "set_id must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        Set<String> ids = cases.stream().map(Case::id).collect(Collectors.toSet());
        if (ids.size() != cases.size()) {
            throw new IllegalArgumentException("case ids must be unique");
        }
    }

    static RegressionSet load(String resource) {
        try (InputStream in = RegressionSet.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("regression set not found: " + resource);
            }
            return JSON.readValue(in, RegressionSet.class);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read regression set " + resource, exception);
        }
    }

    /** 케이스 하나를 프롬프트 입력으로 바꾼다. 턴 ID 는 케이스마다 고정이다. */
    RoleplayTurnInput toInput(Case turn) {
        List<RoleplayTurnInput.MicroGoal> goals =
                scenario.microGoals().stream()
                        .map(
                                goal ->
                                        new RoleplayTurnInput.MicroGoal(
                                                goal.id(),
                                                goal.description(),
                                                goal.requiredEvidence(),
                                                turn.statusOf(goal.key())))
                        .toList();
        State state = turn.state();
        return new RoleplayTurnInput(
                new RoleplayTurnInput.Scenario(
                        scenario.learningGoal(),
                        scenario.scenarioLevel(),
                        scenario.scenarioFacts(),
                        scenario.prohibitedInferences(),
                        goals),
                new RoleplayTurnInput.State(
                        scenario.goal(state.currentGoal()).id(),
                        state.supportLevel(),
                        state.supportTurn(),
                        state.promptCount(),
                        state.answerRevealed(),
                        state.turnCount(),
                        policy.maximumTurnCount(),
                        policy.maximumSupportTurnsPerMicroGoal()),
                new RoleplayTurnInput.LearnerTurn(turnId(turn), turn.llmUtterance()),
                turn.recentDialogue().stream()
                        .map(
                                line ->
                                        new RoleplayTurnInput.DialogueLine(
                                                line.speaker(), line.text()))
                        .toList());
    }

    /** 케이스 순서로 정한 고정 턴 ID. 실행마다 같은 값이 나와야 모델별 결과를 줄 단위로 맞춰 볼 수 있다. */
    UUID turnId(Case turn) {
        int index = cases.indexOf(turn);
        if (index < 0) throw new IllegalArgumentException("case is not part of this set");
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(201 + index));
    }

    record Scenario(
            String sourceId,
            String title,
            String learningGoal,
            String scenarioLevel,
            List<String> scenarioFacts,
            List<String> prohibitedInferences,
            List<Goal> microGoals) {

        Goal goal(String key) {
            return microGoals.stream()
                    .filter(goal -> goal.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown goal key " + key));
        }
    }

    record Goal(String key, UUID id, String description, List<String> requiredEvidence) {}

    record Policy(int maximumTurnCount, int maximumSupportTurnsPerMicroGoal, String note) {}

    record Case(
            String id,
            String suite,
            String purpose,
            String utterance,
            String canonicalUtterance,
            Map<String, String> goalStatus,
            State state,
            List<Line> recentDialogue,
            Expect expect) {

        Case {
            Objects.requireNonNull(id, "case id must not be null");
            Objects.requireNonNull(utterance, "utterance must not be null");
            Objects.requireNonNull(state, "state must not be null");
            goalStatus = goalStatus == null ? Map.of() : Map.copyOf(goalStatus);
            recentDialogue = recentDialogue == null ? List.of() : List.copyOf(recentDialogue);
            expect = expect == null ? Expect.NONE : expect;
        }

        /** LLM 에 들어가는 발화. 개인정보가 있는 케이스는 입력 처리가 가린 문장을 쓴다. */
        String llmUtterance() {
            return canonicalUtterance == null ? utterance : canonicalUtterance;
        }

        String statusOf(String goalKey) {
            return goalStatus.getOrDefault(goalKey, "NOT_OBSERVED");
        }
    }

    record State(
            String currentGoal,
            String supportLevel,
            int supportTurn,
            int promptCount,
            boolean answerRevealed,
            int turnCount) {}

    record Line(RoleplayTurnInput.Speaker speaker, String text) {}

    /** 비어 있는 항목은 검사하지 않는다. 여러 값이 있으면 그중 하나면 통과다. */
    record Expect(
            List<String> responseActs,
            List<String> learningStates,
            List<String> notLearningStates,
            List<String> strategyTypes,
            List<String> supportLevels,
            List<String> responseTypes,
            List<String> forbiddenResponseTerms) {

        static final Expect NONE = new Expect(null, null, null, null, null, null, null);

        Expect {
            responseActs = orEmpty(responseActs);
            learningStates = orEmpty(learningStates);
            notLearningStates = orEmpty(notLearningStates);
            strategyTypes = orEmpty(strategyTypes);
            supportLevels = orEmpty(supportLevels);
            responseTypes = orEmpty(responseTypes);
            forbiddenResponseTerms = orEmpty(forbiddenResponseTerms);
        }

        private static List<String> orEmpty(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }
}
