package com.neuringo.neuringobe.ai.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.report.domain.DifficultyBasis;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.QuizEvidence;
import com.neuringo.neuringobe.report.domain.ReportEvidence;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 학습 리포트 회귀 세트(src/test/resources/eval/report-*.json). 사례마다 리포트 근거와, 루프 규칙과 따로 확인할 기대값(expect)을 둔다.
 * goal_library 와 similarity_probes 는 임베딩 유사도 실측에 쓴다.
 */
record ReportRegressionSet(
        String setId,
        String description,
        List<Case> cases,
        List<String> goalLibrary,
        List<Probe> similarityProbes) {

    static final String DEFAULT_RESOURCE = "eval/report-regression-v1.json";

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    ReportRegressionSet {
        Objects.requireNonNull(setId, "set_id must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        goalLibrary = goalLibrary == null ? List.of() : List.copyOf(goalLibrary);
        similarityProbes = similarityProbes == null ? List.of() : List.copyOf(similarityProbes);
        Set<String> ids = cases.stream().map(Case::id).collect(Collectors.toSet());
        if (ids.size() != cases.size())
            throw new IllegalArgumentException("case ids must be unique");
    }

    static ReportRegressionSet load(String resource) {
        try (InputStream input =
                ReportRegressionSet.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalArgumentException("set not found: " + resource);
            return JSON.readValue(input, ReportRegressionSet.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Case(
            String id,
            String purpose,
            QuizEvidence quiz,
            List<Goal> goals,
            List<String> technical,
            Difficulty difficulty,
            Expect expect) {

        Case {
            goals = goals == null ? List.of() : List.copyOf(goals);
            technical = technical == null ? List.of() : List.copyOf(technical);
            expect = expect == null ? new Expect(List.of(), List.of(), List.of()) : expect;
        }

        ReportEvidence evidence() {
            List<GoalEvidence> goalEvidence =
                    goals.stream()
                            .map(
                                    goal ->
                                            new GoalEvidence(
                                                    new GoalOutcome(
                                                            UUID.nameUUIDFromBytes(
                                                                    (id + goal.title())
                                                                            .getBytes(
                                                                                    StandardCharsets
                                                                                            .UTF_8)),
                                                            goal.start(),
                                                            goal.finalLevel(),
                                                            goal.result()),
                                                    goal.title(),
                                                    goal.observations(),
                                                    goal.supports()))
                            .toList();
            DifficultyBasis basis =
                    difficulty == null
                            ? null
                            : new DifficultyBasis(
                                    difficulty.decisionType(),
                                    difficulty.scenarioLevel(),
                                    difficulty.reasonCode(),
                                    difficulty.policyVersion());
            return new ReportEvidence(quiz, goalEvidence, technical, basis);
        }
    }

    record Goal(
            String title,
            SupportLevel start,
            @JsonProperty("final") SupportLevel finalLevel,
            GoalOutcome.Result result,
            List<String> observations,
            List<String> supports) {}

    record Difficulty(
            DifficultyBasis.DecisionType decisionType,
            ScenarioLevel scenarioLevel,
            String reasonCode,
            String policyVersion) {}

    /** 루프 규칙과 따로 보는 기대값. 섹션 이름은 observations·effective_supports·difficult_points. */
    record Expect(List<String> nonEmpty, List<String> empty, List<String> forbidden) {

        Expect {
            nonEmpty = nonEmpty == null ? List.of() : List.copyOf(nonEmpty);
            empty = empty == null ? List.of() : List.copyOf(empty);
            forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        }
    }

    record Probe(String query, String duplicate, List<String> related, List<String> unrelated) {

        Probe {
            related = related == null ? List.of() : List.copyOf(related);
            unrelated = unrelated == null ? List.of() : List.copyOf(unrelated);
        }
    }
}
