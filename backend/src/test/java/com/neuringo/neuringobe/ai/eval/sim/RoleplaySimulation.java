package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.ai.eval.sim.SimSpec.ChildProfile;
import com.neuringo.neuringobe.ai.eval.sim.SimSpec.LearningGoal;
import com.neuringo.neuringobe.report.application.ComposedReport;
import com.neuringo.neuringobe.report.application.LearningReportComposer;
import com.neuringo.neuringobe.report.application.Recommendation;
import com.neuringo.neuringobe.report.application.RecommendationComposer;
import com.neuringo.neuringobe.report.application.RecommendationRequest;
import com.neuringo.neuringobe.report.application.ReportRequest;
import com.neuringo.neuringobe.report.domain.DifficultyBasis;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import com.neuringo.neuringobe.report.domain.ReportEvidence;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayGoalSummarizer;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayMicroGoal;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplaySummary;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayTurnRecord;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyPolicy;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 합성 아동 한 명에게 역할극을 여러 번 이어서 시킨다. 한 회차는 역할극 → 목표별 결과 → (리포트) → 다음 난이도 → 추천 → 강사 선택 → 다음 회차다.
 *
 * <p>강사 선택은 사람이 할 일이라 규칙으로 흉내 낸다. 확장 추천이 있으면 그것을, 없으면 추천 순서의 첫 번째를 고른다. 확장이면 추천된 학습 목표로 바꾸고, 나머지는
 * 같은 학습 목표를 다른 장면으로 한 번 더 한다고 본다.
 */
final class RoleplaySimulation {

    /** 팀 develop 최초 난이도 정책(InitialDifficultyPolicy)이 짝지은 난이도·시작 지원. 후속 회차도 같은 짝을 쓴다고 가정한다. */
    static final Map<ScenarioLevel, SupportLevel> START_SUPPORT =
            Map.of(
                    ScenarioLevel.L1,
                    SupportLevel.S2,
                    ScenarioLevel.L2,
                    SupportLevel.S1,
                    ScenarioLevel.L3,
                    SupportLevel.S0);

    static final UUID CLASS_ID =
            UUID.nameUUIDFromBytes("sim-class".getBytes(StandardCharsets.UTF_8));

    record Session(
            int number,
            LearningGoal goal,
            ScenarioLevel level,
            SupportLevel startSupport,
            List<RoleplayTurnRecord> turns,
            SimRoleplayEngine.EndReason endReason,
            RoleplaySummary summary,
            FollowUpDifficultyDecision decision,
            List<Recommendation> recommendations,
            Recommendation chosen,
            ComposedReport report,
            Map<String, Double> abilityBefore,
            ScenarioLevel appropriate,
            Map<ScenarioLevel, Double> expectedScore) {}

    record ChildRun(ChildProfile child, long seed, List<Session> sessions) {}

    /**
     * 실험마다 바꾸는 값.
     *
     * @param appropriateRuns 0 보다 크면 회차마다 그 아이의 지금 실력으로 L1~L3 를 이만큼씩 미리 돌려 적정 난이도를 잰다
     */
    record Config(
            SimRoleplayEngine.Rules rules,
            SimSpec.Session limits,
            SimChild.Knobs knobs,
            int appropriateRuns,
            double appropriateThreshold,
            boolean criteria) {

        static Config of(SimSpec spec) {
            return new Config(
                    SimRoleplayEngine.Rules.TEAM,
                    spec.session(),
                    SimChild.Knobs.DEFAULT,
                    0,
                    0.5,
                    false);
        }
    }

    private final SimSpec spec;
    private final Config config;
    private final SimRoleplayEngine engine;
    private final RoleplayGoalSummarizer summarizer = new RoleplayGoalSummarizer();
    private final FollowUpDifficultyPolicy difficulty;
    private final RecommendationComposer recommendations;
    private final LearningReportComposer reports;

    /**
     * @param reports null 이면 리포트를 만들지 않는다(LLM 호출 없음).
     */
    RoleplaySimulation(
            SimSpec spec,
            FollowUpDifficultyPolicy difficulty,
            RecommendationComposer recommendations,
            LearningReportComposer reports) {
        this(spec, Config.of(spec), difficulty, recommendations, reports);
    }

    RoleplaySimulation(
            SimSpec spec,
            Config config,
            FollowUpDifficultyPolicy difficulty,
            RecommendationComposer recommendations,
            LearningReportComposer reports) {
        this.spec = spec;
        this.config = config;
        this.engine = new SimRoleplayEngine(config.limits(), config.rules());
        this.difficulty = difficulty;
        this.recommendations = recommendations;
        this.reports = reports;
    }

    ChildRun run(ChildProfile profile, long seed, int sessionCount) {
        SimChild child = new SimChild(profile, seed, config.knobs());
        ScenarioLevel level = ScenarioLevel.valueOf(profile.initialLevel());
        int candidates = 0;
        LearningGoal goal = spec.goal(spec.startGoal());
        Set<UUID> completed = new LinkedHashSet<>();
        FollowUpDifficultyDecision previous = null;
        List<Session> sessions = new ArrayList<>();

        for (int number = 1; number <= sessionCount; number++) {
            SupportLevel start = START_SUPPORT.get(level);
            List<UUID> microIds = goal.micro().stream().map(goal::microGoalId).toList();
            Map<String, Double> abilityBefore = new java.util.LinkedHashMap<>();
            goal.micro().forEach(skill -> abilityBefore.put(skill, child.ability(skill)));
            List<RoleplayMicroGoal> microGoals = new ArrayList<>();
            for (int i = 0; i < microIds.size(); i++) {
                microGoals.add(
                        new RoleplayMicroGoal(
                                microIds.get(i), spec.skills().get(goal.micro().get(i))));
            }
            Map<ScenarioLevel, Double> expected =
                    config.appropriateRuns() > 0
                            ? expectedScores(
                                    child, goal, microIds, microGoals, seed * 1_000 + number)
                            : Map.of();

            List<String> criteria = config.criteria() ? goal.criteriaInOrder() : null;
            SimRoleplayEngine.Result played =
                    engine.run(microIds, goal.micro(), criteria, start, level, child);
            RoleplaySummary summary = summarizer.summarize(microGoals, played.turns(), start);
            FollowUpDifficultyDecision decision =
                    difficulty.decide(level, summary.outcomes(), candidates);
            List<Recommendation> recs =
                    recommendations.compose(
                            new RecommendationRequest(
                                    CLASS_ID, goal.id(), goal.title(), completed, summary.goals()));
            Recommendation chosen = choose(recs);
            ComposedReport report =
                    reports == null ? null : report(goal, level, previous, summary, number);

            sessions.add(
                    new Session(
                            number,
                            goal,
                            level,
                            start,
                            played.turns(),
                            played.endReason(),
                            summary,
                            decision,
                            recs,
                            chosen,
                            report,
                            abilityBefore,
                            appropriate(expected, config.appropriateThreshold()),
                            expected));

            for (int i = 0; i < goal.micro().size(); i++) {
                child.learn(goal.micro().get(i), summary.outcomes().get(i));
            }
            completed.add(goal.id());
            goal = next(goal, chosen);
            level = decision.nextLevel();
            candidates = decision.consecutiveUpgradeCandidates();
            previous = decision;
        }
        return new ChildRun(profile, seed, sessions);
    }

    /**
     * 지금 실력의 복제로 L1~L3 를 미리 돌려 본 평균 점수율. 실제 회차의 무작위 흐름에는 영향을 주지 않는다(복제가 다른 시드를 쓴다). 무효 목표만 나온 실행은
     * 평균에서 뺀다.
     */
    private Map<ScenarioLevel, Double> expectedScores(
            SimChild child,
            LearningGoal goal,
            List<UUID> microIds,
            List<RoleplayMicroGoal> microGoals,
            long seedBase) {
        Map<ScenarioLevel, Double> expected = new java.util.EnumMap<>(ScenarioLevel.class);
        for (ScenarioLevel candidate : ScenarioLevel.values()) {
            SupportLevel start = START_SUPPORT.get(candidate);
            double sum = 0;
            int counted = 0;
            for (int run = 0; run < config.appropriateRuns(); run++) {
                SimChild probe = child.copy(seedBase * 31 + candidate.ordinal() * 7_919L + run);
                SimRoleplayEngine.Result played =
                        engine.run(microIds, goal.micro(), start, candidate, probe);
                RoleplaySummary summary = summarizer.summarize(microGoals, played.turns(), start);
                var rate =
                        com.neuringo.neuringobe.scenario.domain.GoalScoreRate.of(
                                summary.outcomes());
                if (rate.goalCount() == 0) continue;
                sum += rate.ratio();
                counted++;
            }
            expected.put(candidate, counted == 0 ? 0.0 : sum / counted);
        }
        return expected;
    }

    /** 평균 점수율이 threshold(기본 50% = 하향 기준) 이상인 가장 높은 난이도. 어느 난이도도 못 미치면 L1. */
    static ScenarioLevel appropriate(Map<ScenarioLevel, Double> expected, double threshold) {
        if (expected.isEmpty()) return null;
        ScenarioLevel best = ScenarioLevel.L1;
        for (ScenarioLevel level : ScenarioLevel.values()) {
            if (expected.get(level) >= threshold) best = level;
        }
        return best;
    }

    private static Recommendation choose(List<Recommendation> recs) {
        Optional<Recommendation> extend =
                recs.stream()
                        .filter(
                                r ->
                                        r.candidate().type() == RecommendationType.EXTEND
                                                && r.suggestedGoal() != null)
                        .findFirst();
        return extend.orElse(recs.isEmpty() ? null : recs.getFirst());
    }

    private LearningGoal next(LearningGoal current, Recommendation chosen) {
        if (chosen != null
                && chosen.candidate().type() == RecommendationType.EXTEND
                && chosen.suggestedGoal() != null) {
            return spec.goal(chosen.suggestedGoal().goalId());
        }
        return current;
    }

    private ComposedReport report(
            LearningGoal goal,
            ScenarioLevel level,
            FollowUpDifficultyDecision previous,
            RoleplaySummary summary,
            int number) {
        DifficultyBasis basis =
                previous == null
                        ? new DifficultyBasis(
                                DifficultyBasis.DecisionType.INITIAL,
                                level,
                                "INITIAL_ASSUMED",
                                "INITIAL_QUIZ_V2")
                        : new DifficultyBasis(
                                DifficultyBasis.DecisionType.FOLLOW_UP,
                                level,
                                previous.reason().name(),
                                previous.policyVersion());
        ReportEvidence evidence =
                new ReportEvidence(null, summary.goals(), summary.technicalExclusions(), basis);
        UUID activityId =
                UUID.nameUUIDFromBytes(
                        (goal.key() + ":" + number).getBytes(StandardCharsets.UTF_8));
        return reports.compose(
                new ReportRequest(
                        UUID.randomUUID(), activityId, CLASS_ID, UUID.randomUUID(), evidence));
    }
}
