package com.neuringo.neuringobe.ai.eval.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.eval.sim.SimSpec.ChildProfile;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.RecommendationCandidate;
import com.neuringo.neuringobe.report.domain.RecommendationPolicy;
import com.neuringo.neuringobe.report.domain.RecommendationRules;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import com.neuringo.neuringobe.report.domain.roleplay.LearningState;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayGoalSummarizer;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayMicroGoal;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplaySummary;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayTurnRecord;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Direction;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyPolicy;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyRules;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 무작위로 만든 입력 수천 개로 변환기·다음 난이도·추천·역할극 엔진이 지켜야 할 성질을 확인한다. 실패하면 메시지의 case 번호가 시드다.
 *
 * <p>개별 예시 테스트(RoleplayGoalSummarizerTest, FollowUpDifficultyPolicyTest, RecommendationPolicyTest)가
 * "이 입력이면 이 결과"를 본다면, 여기서는 입력을 바꿔도 지켜져야 할 관계를 본다. 기술 오류로 뺀 목표를 더해도 결정이 같아야 하고, 한 목표가 좋아지면 결정이 나빠지면
 * 안 된다.
 */
class RoleplayPipelineInvariantTest {

    private static final int CASES = 3000;
    private static final SupportLevel[] S = SupportLevel.values();
    private static final LearningState[] STATES = LearningState.values();
    private static final Set<Result> EXCLUDED =
            EnumSet.of(
                    Result.EXCLUDED_TECHNICAL_ERROR,
                    Result.EXCLUDED_INTERRUPTED,
                    Result.EXCLUDED_REFUSED);

    private final RoleplayGoalSummarizer summarizer = new RoleplayGoalSummarizer();
    private final FollowUpDifficultyPolicy difficulty =
            new FollowUpDifficultyPolicy(FollowUpDifficultyRules.V1);
    private final RecommendationPolicy recommendations =
            new RecommendationPolicy(RecommendationRules.V1);

    // ---------- 변환기 ----------

    @Test
    void summarizerMatchesItsRulesForArbitraryTurnSequences() {
        for (int seed = 0; seed < CASES; seed++) {
            Random random = new Random(seed);
            List<RoleplayMicroGoal> goals = goals(1 + random.nextInt(4));
            List<RoleplayTurnRecord> turns = randomTurns(random, goals);
            RoleplaySummary summary = summarizer.summarize(goals, turns, S[random.nextInt(4)]);

            assertThat(summary.goals()).as("case %d", seed).hasSize(goals.size());
            for (int g = 0; g < goals.size(); g++) {
                UUID id = goals.get(g).id();
                List<RoleplayTurnRecord> mine =
                        turns.stream().filter(t -> t.microGoalId().equals(id)).toList();
                List<RoleplayTurnRecord> assessable =
                        mine.stream()
                                .filter(t -> t.learningState() != LearningState.NOT_ASSESSABLE)
                                .toList();
                GoalEvidence evidence = summary.goals().get(g);
                GoalOutcome outcome = evidence.outcome();
                assertThat(outcome.goalId()).as("case %d order", seed).isEqualTo(id);
                assertThat(evidence.observations().size())
                        .as("case %d", seed)
                        .isLessThanOrEqualTo(3);
                for (int i = 1; i < evidence.observations().size(); i++) {
                    assertThat(evidence.observations().get(i))
                            .as("case %d no repeated sentence", seed)
                            .isNotEqualTo(evidence.observations().get(i - 1));
                }
                if (mine.isEmpty()) {
                    assertThat(outcome.result())
                            .as("case %d", seed)
                            .isEqualTo(Result.EXCLUDED_INTERRUPTED);
                    continue;
                }
                assertThat(outcome.startSupport())
                        .as("case %d start", seed)
                        .isEqualTo(mine.getFirst().inputSupport());
                if (assessable.isEmpty()) {
                    assertThat(outcome.result())
                            .as("case %d", seed)
                            .isEqualTo(Result.EXCLUDED_TECHNICAL_ERROR);
                } else if (assessable.stream().allMatch(RoleplayTurnRecord::refusal)) {
                    assertThat(outcome.result())
                            .as("case %d", seed)
                            .isEqualTo(Result.EXCLUDED_REFUSED);
                } else {
                    RoleplayTurnRecord achieved =
                            assessable.stream()
                                    .filter(t -> t.learningState() == LearningState.GOAL_ACHIEVED)
                                    .findFirst()
                                    .orElse(null);
                    assertThat(outcome.result())
                            .as("case %d", seed)
                            .isEqualTo(achieved == null ? Result.NOT_REACHED : Result.REACHED);
                    assertThat(outcome.finalSupport())
                            .as("case %d final", seed)
                            .isEqualTo(
                                    achieved == null
                                            ? assessable.getLast().inputSupport()
                                            : achieved.inputSupport());
                }
                boolean extra =
                        outcome.result() == Result.REACHED
                                && outcome.finalSupport().ordinal()
                                        > outcome.startSupport().ordinal();
                assertThat(evidence.effectiveSupports().isEmpty())
                        .as("case %d supports", seed)
                        .isEqualTo(!extra);
            }
            boolean unclear =
                    turns.stream().anyMatch(t -> t.learningState() == LearningState.NOT_ASSESSABLE);
            assertThat(summary.technicalExclusions().isEmpty())
                    .as("case %d", seed)
                    .isEqualTo(!unclear);
        }
    }

    @Test
    void summarizerIgnoresInputOrderAndUnclearRepeatsAtTheSameSupport() {
        for (int seed = 0; seed < CASES; seed++) {
            Random random = new Random(seed);
            List<RoleplayMicroGoal> goals = goals(1 + random.nextInt(4));
            List<RoleplayTurnRecord> turns = randomTurns(random, goals);
            List<GoalOutcome> base = summarizer.summarize(goals, turns, SupportLevel.S1).outcomes();

            List<RoleplayTurnRecord> shuffled = new ArrayList<>(turns);
            Collections.shuffle(shuffled, random);
            assertThat(summarizer.summarize(goals, shuffled, SupportLevel.S1).outcomes())
                    .as("case %d shuffled", seed)
                    .isEqualTo(base);

            if (turns.isEmpty()) continue;
            // 어떤 답 바로 앞에 같은 목표·같은 지원 단계의 판단 불가 답이 끼어도(음성 인식 실패 후 다시 말함) 결과는 같아야 한다.
            int at = random.nextInt(turns.size());
            List<RoleplayTurnRecord> withUnclear = new ArrayList<>();
            for (int i = 0; i < turns.size(); i++) {
                RoleplayTurnRecord t = turns.get(i);
                if (i == at) {
                    withUnclear.add(
                            new RoleplayTurnRecord(
                                    t.microGoalId(),
                                    withUnclear.size() + 1,
                                    t.inputSupport(),
                                    LearningState.NOT_ASSESSABLE,
                                    false));
                }
                withUnclear.add(
                        new RoleplayTurnRecord(
                                t.microGoalId(),
                                withUnclear.size() + 1,
                                t.inputSupport(),
                                t.learningState(),
                                t.refusal()));
            }
            assertThat(summarizer.summarize(goals, withUnclear, SupportLevel.S1).outcomes())
                    .as("case %d unclear inserted", seed)
                    .isEqualTo(base);
        }
    }

    // ---------- 다음 난이도 ----------

    @Test
    void difficultyMovesAtMostOneStepAndNeedsTwoValidGoals() {
        for (int seed = 0; seed < CASES; seed++) {
            Random random = new Random(seed);
            List<GoalOutcome> outcomes = randomOutcomes(random, random.nextInt(6));
            ScenarioLevel level = ScenarioLevel.values()[random.nextInt(3)];
            FollowUpDifficultyDecision decision =
                    difficulty.decide(level, outcomes, random.nextInt(3));

            assertThat(Math.abs(decision.nextLevel().ordinal() - level.ordinal()))
                    .as("case %d", seed)
                    .isLessThanOrEqualTo(1);
            long valid = outcomes.stream().filter(GoalOutcome::valid).count();
            if (valid < 2) {
                assertThat(decision.direction()).as("case %d", seed).isEqualTo(Direction.KEEP);
                assertThat(decision.reason())
                        .as("case %d", seed)
                        .isEqualTo(FollowUpDifficultyDecision.Reason.INSUFFICIENT_VALID_GOALS);
            }
            if (decision.direction() == Direction.RAISE) {
                assertThat(
                                outcomes.stream()
                                        .filter(GoalOutcome::valid)
                                        .noneMatch(GoalOutcome::neededAnswerOrMissed))
                        .as("case %d raise without S3 or missed goals", seed)
                        .isTrue();
            }
        }
    }

    @Test
    void excludedGoalsNeverChangeTheDifficultyDecisionOrRecommendations() {
        for (int seed = 0; seed < CASES; seed++) {
            Random random = new Random(seed);
            List<GoalOutcome> outcomes = randomOutcomes(random, random.nextInt(5));
            ScenarioLevel level = ScenarioLevel.values()[random.nextInt(3)];
            int candidates = random.nextInt(3);
            List<GoalOutcome> more = new ArrayList<>(outcomes);
            Result excluded = new ArrayList<>(EXCLUDED).get(random.nextInt(3));
            more.add(
                    random.nextInt(more.size() + 1),
                    new GoalOutcome(UUID.randomUUID(), S[random.nextInt(4)], S[3], excluded));

            assertThat(
                            sameDecision(
                                    difficulty.decide(level, outcomes, candidates),
                                    difficulty.decide(level, more, candidates)))
                    .as("case %d difficulty with %s", seed, excluded)
                    .isTrue();
            assertThat(bases(recommendations.recommend(evidence(more))))
                    .as("case %d recommendations with %s", seed, excluded)
                    .isEqualTo(bases(recommendations.recommend(evidence(outcomes))));
        }
    }

    @Test
    void aBetterResultOnOneGoalNeverMakesTheDifficultyDecisionWorse() {
        int improved = 0;
        for (int seed = 0; seed < CASES; seed++) {
            Random random = new Random(seed);
            List<GoalOutcome> outcomes = randomOutcomes(random, 1 + random.nextInt(5));
            int index = random.nextInt(outcomes.size());
            GoalOutcome better = improve(outcomes.get(index));
            if (better == null) continue;
            improved++;
            List<GoalOutcome> after = new ArrayList<>(outcomes);
            after.set(index, better);
            ScenarioLevel level = ScenarioLevel.L2;
            int candidates = random.nextInt(2);
            FollowUpDifficultyDecision before = difficulty.decide(level, outcomes, candidates);
            FollowUpDifficultyDecision now = difficulty.decide(level, after, candidates);
            assertThat(rank(now.direction()))
                    .as(
                            "case %d: %s -> %s (%s -> %s)",
                            seed, outcomes.get(index), better, before.reason(), now.reason())
                    .isGreaterThanOrEqualTo(rank(before.direction()));
            assertThat(now.scoreRate().scaledPoints())
                    .as("case %d score", seed)
                    .isGreaterThanOrEqualTo(before.scoreRate().scaledPoints());
        }
        assertThat(improved).isGreaterThan(CASES / 3);
    }

    @Test
    void difficultyDoesNotDependOnGoalOrder() {
        for (int seed = 0; seed < CASES; seed++) {
            Random random = new Random(seed);
            List<GoalOutcome> outcomes = randomOutcomes(random, random.nextInt(6));
            List<GoalOutcome> shuffled = new ArrayList<>(outcomes);
            Collections.shuffle(shuffled, random);
            assertThat(
                            sameDecision(
                                    difficulty.decide(ScenarioLevel.L2, outcomes, 1),
                                    difficulty.decide(ScenarioLevel.L2, shuffled, 1)))
                    .as("case %d", seed)
                    .isTrue();
        }
    }

    // ---------- 추천 ----------

    @Test
    void recommendationsFollowTheirTypeRulesAndNeverUseExcludedGoals() {
        for (int seed = 0; seed < CASES; seed++) {
            Random random = new Random(seed);
            List<GoalEvidence> goals = evidence(randomOutcomes(random, random.nextInt(6)));
            List<RecommendationCandidate> recs = recommendations.recommend(goals);

            assertThat(recs.stream().map(RecommendationCandidate::type).distinct().count())
                    .as("case %d one per type", seed)
                    .isEqualTo(recs.size());
            List<RecommendationType> order = RecommendationRules.V1.order();
            for (int i = 1; i < recs.size(); i++) {
                assertThat(order.indexOf(recs.get(i).type()))
                        .as("case %d order", seed)
                        .isGreaterThan(order.indexOf(recs.get(i - 1).type()));
            }
            for (RecommendationCandidate rec : recs) {
                if (rec.basis() == null) continue;
                GoalOutcome basis = rec.basis().outcome();
                assertThat(basis.valid())
                        .as("case %d %s basis must be valid", seed, rec.type())
                        .isTrue();
                switch (rec.type()) {
                    case REINFORCE ->
                            assertThat(basis.neededAnswerOrMissed()).as("case %d", seed).isTrue();
                    case REPEAT ->
                            assertThat(
                                            basis.reachedWithoutAnswer()
                                                    && !basis.reachedWithoutExtraSupport())
                                    .as("case %d", seed)
                                    .isTrue();
                    case APPLY ->
                            assertThat(basis.reachedWithoutExtraSupport())
                                    .as("case %d", seed)
                                    .isTrue();
                    case EXTEND -> {}
                }
            }
            boolean extend = recs.stream().anyMatch(r -> r.type() == RecommendationType.EXTEND);
            if (extend) {
                assertThat(
                                goals.stream()
                                        .map(GoalEvidence::outcome)
                                        .filter(GoalOutcome::valid)
                                        .noneMatch(GoalOutcome::neededAnswerOrMissed))
                        .as("case %d extend without S3 or missed goals", seed)
                        .isTrue();
            }
        }
    }

    // ---------- 역할극 엔진 ----------

    @Test
    void engineVisitsGoalsInOrderAndOnlyMovesSupportByTheTeamRules() {
        for (int seed = 0; seed < 1000; seed++) {
            Random random = new Random(seed);
            SimSpec.Session limits =
                    new SimSpec.Session(4 + random.nextInt(14), 1 + random.nextInt(4));
            SimRoleplayEngine engine = new SimRoleplayEngine(limits);
            int goalCount = 1 + random.nextInt(4);
            List<UUID> ids =
                    IntStream.range(0, goalCount).mapToObj(i -> UUID.randomUUID()).toList();
            List<String> skills = IntStream.range(0, goalCount).mapToObj(i -> "K" + i).toList();
            ChildProfile profile =
                    new ChildProfile(
                            "R",
                            "random",
                            "random",
                            "L2",
                            Map.of("default", random.nextDouble() * 1.2 - 0.2),
                            0,
                            random.nextDouble() * 0.4,
                            random.nextDouble() * 0.3,
                            random.nextDouble() * 0.1);
            SupportLevel start = S[random.nextInt(4)];
            SimRoleplayEngine.Result result =
                    engine.run(
                            ids,
                            skills,
                            start,
                            ScenarioLevel.values()[random.nextInt(3)],
                            new SimChild(profile, seed));

            List<RoleplayTurnRecord> turns = result.turns();
            assertThat(turns.size()).as("case %d", seed).isLessThanOrEqualTo(limits.maxTurns());
            int goal = 0;
            int askedHere = 0;
            for (int i = 0; i < turns.size(); i++) {
                RoleplayTurnRecord turn = turns.get(i);
                assertThat(turn.turnNumber()).as("case %d", seed).isEqualTo(i + 1);
                int index = ids.indexOf(turn.microGoalId());
                assertThat(index).as("case %d goals in order", seed).isBetween(goal, goal + 1);
                if (i == 0) {
                    assertThat(turn.inputSupport()).as("case %d", seed).isEqualTo(start);
                } else {
                    RoleplayTurnRecord previous = turns.get(i - 1);
                    int step = turn.inputSupport().ordinal() - previous.inputSupport().ordinal();
                    if (index == goal) {
                        // 같은 목표 안에서는 그대로, 한 단계 위, 또는 상한에 닿아 S3 로만 간다. 내려가지 않는다.
                        assertThat(step == 0 || step == 1 || turn.inputSupport() == SupportLevel.S3)
                                .as("case %d step %d", seed, step)
                                .isTrue();
                        assertThat(previous.learningState())
                                .as("case %d", seed)
                                .isNotEqualTo(LearningState.GOAL_ACHIEVED);
                        askedHere++;
                    } else {
                        boolean achieved = previous.learningState() == LearningState.GOAL_ACHIEVED;
                        assertThat(achieved || previous.inputSupport() == SupportLevel.S3)
                                .as("case %d moved on only after achieving or S3", seed)
                                .isTrue();
                        askedHere = 0;
                    }
                }
                assertThat(askedHere)
                        .as("case %d asks on one goal", seed)
                        .isLessThanOrEqualTo(limits.maxSupportTurnsPerGoal());
                goal = index;
            }
        }
    }

    // ---------- 도우미 ----------

    private static List<RoleplayMicroGoal> goals(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new RoleplayMicroGoal(UUID.randomUUID(), "목표 " + i))
                .toList();
    }

    private static List<RoleplayTurnRecord> randomTurns(
            Random random, List<RoleplayMicroGoal> goals) {
        int count = random.nextInt(14);
        List<RoleplayTurnRecord> turns = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID goal = goals.get(random.nextInt(goals.size())).id();
            LearningState state = STATES[random.nextInt(STATES.length)];
            boolean refusal = state == LearningState.OFF_TOPIC && random.nextBoolean();
            turns.add(new RoleplayTurnRecord(goal, i + 1, S[random.nextInt(4)], state, refusal));
        }
        return turns;
    }

    private static List<GoalOutcome> randomOutcomes(Random random, int count) {
        List<GoalOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SupportLevel start = S[random.nextInt(4)];
            SupportLevel last = S[start.ordinal() + random.nextInt(4 - start.ordinal())];
            double roll = random.nextDouble();
            Result result =
                    roll < 0.5
                            ? Result.REACHED
                            : roll < 0.75
                                    ? Result.NOT_REACHED
                                    : new ArrayList<>(EXCLUDED).get(random.nextInt(3));
            outcomes.add(new GoalOutcome(UUID.randomUUID(), start, last, result));
        }
        return outcomes;
    }

    /** 같은 시작에서 한 단계 덜 도움을 받고 도달했거나, 미도달이 같은 단계에서 도달로 바뀐 결과. 더 나아질 수 없으면 null. */
    private static GoalOutcome improve(GoalOutcome outcome) {
        if (outcome.result() == Result.NOT_REACHED && outcome.finalSupport() != SupportLevel.S3) {
            return new GoalOutcome(
                    outcome.goalId(),
                    outcome.startSupport(),
                    outcome.finalSupport(),
                    Result.REACHED);
        }
        if (outcome.result() == Result.REACHED
                && outcome.finalSupport().ordinal() > outcome.startSupport().ordinal()) {
            return new GoalOutcome(
                    outcome.goalId(),
                    outcome.startSupport(),
                    S[outcome.finalSupport().ordinal() - 1],
                    Result.REACHED);
        }
        return null;
    }

    private static List<GoalEvidence> evidence(List<GoalOutcome> outcomes) {
        return outcomes.stream()
                .map(o -> new GoalEvidence(o, "목표", List.of("관찰"), List.of()))
                .toList();
    }

    private static List<String> bases(List<RecommendationCandidate> recs) {
        return recs.stream()
                .map(r -> r.type() + ":" + (r.basis() == null ? "-" : r.basis().outcome().goalId()))
                .toList();
    }

    private static boolean sameDecision(
            FollowUpDifficultyDecision a, FollowUpDifficultyDecision b) {
        return a.nextLevel() == b.nextLevel()
                && a.direction() == b.direction()
                && a.reason() == b.reason()
                && a.scoreRate().equals(b.scoreRate())
                && a.consecutiveUpgradeCandidates() == b.consecutiveUpgradeCandidates();
    }

    private static int rank(Direction direction) {
        return switch (direction) {
            case LOWER -> 0;
            case KEEP -> 1;
            case RAISE -> 2;
        };
    }
}
