package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.ai.eval.EvalReportComposers;
import com.neuringo.neuringobe.ai.eval.sim.RoleplaySimulation.ChildRun;
import com.neuringo.neuringobe.ai.eval.sim.SimSpec.ChildProfile;
import com.neuringo.neuringobe.ai.eval.sim.SimSpec.LearningGoal;
import com.neuringo.neuringobe.report.application.RecommendationComposer;
import com.neuringo.neuringobe.report.application.similarity.InMemoryGoalSimilaritySearch;
import com.neuringo.neuringobe.report.domain.RecommendationPolicy;
import com.neuringo.neuringobe.report.domain.RecommendationRules;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyPolicy;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 역할극 다회차 시뮬레이션. 합성 아동마다 역할극을 여러 번 이어 시키고, 그때마다 목표별 결과·다음 난이도·추천(·리포트)을 남긴다.
 *
 * <pre>
 * ./gradlew roleplaySim --args="--seeds 30 --sessions 8"
 * ./gradlew roleplaySim --args="--seeds 1 --sessions 6 --children C1,C2 --report --gen haiku55 --review argon4"
 * </pre>
 *
 * <p>결과는 build/roleplay-sim/{run-id}/report.md 에 쓴다. --report 가 없으면 LLM 을 부르지 않는다.
 */
public final class RoleplaySimMain {

    /** 글자 두 개 단위 벡터는 실제 임베딩보다 유사도가 낮게 나와 시뮬레이션에서는 기준을 낮춘다. 실제 임베딩 기준(0.5·0.90)은 그대로다. */
    static final double SIM_MIN_SIMILARITY = 0.2;

    static final double SIM_DUPLICATE_SIMILARITY = 0.90;

    private RoleplaySimMain() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> options = options(args);
        SimSpec spec = SimSpec.load(options.getOrDefault("spec", SimSpec.DEFAULT_RESOURCE));
        if (options.containsKey("sweep")) {
            RoleplaySimSweep.run(spec, options);
            return;
        }
        int seeds = Integer.parseInt(options.getOrDefault("seeds", "30"));
        int sessions = Integer.parseInt(options.getOrDefault("sessions", "8"));
        long detailSeed = Long.parseLong(options.getOrDefault("detail-seed", "1"));
        String runId = options.getOrDefault("run-id", "sim-" + System.currentTimeMillis());
        List<ChildProfile> children =
                options.containsKey("children")
                        ? Arrays.stream(options.get("children").split(","))
                                .map(spec::child)
                                .toList()
                        : spec.children();

        CharBigramEmbedder embedder = new CharBigramEmbedder();
        RecommendationComposer recommendations = recommendationComposer(spec, embedder);
        FollowUpDifficultyRules difficultyRules =
                difficultyRules(options.getOrDefault("difficulty", "v1"));
        FollowUpDifficultyPolicy difficulty = new FollowUpDifficultyPolicy(difficultyRules);
        RoleplaySimulation.Config config = config(spec, options);
        SimRoleplayEngine.Rules rules = config.rules();

        List<String> notes = new ArrayList<>();
        notes.add(
                "역할극 규칙: `"
                        + rules.name()
                        + "` (판단 불가 답을 물은 횟수에 넣음 "
                        + rules.unclearCountsTowardCap()
                        + ", S3 달성 뒤 다음 목표도 S3 "
                        + rules.keepSupportAfterS3Achievement()
                        + ")");
        notes.add(
                "난이도 규칙: `"
                        + difficultyRules.version()
                        + "` (하향 "
                        + difficultyRules.lowerBelowPercent()
                        + "% 미만, 상향 후보 "
                        + difficultyRules.upgradeCandidateAtLeastPercent()
                        + "% 이상, 연속 "
                        + difficultyRules.requiredConsecutiveCandidates()
                        + "번, 빠른 상향 "
                        + difficultyRules.fastUpgradeAtLeastPercent()
                        + ")");
        notes.add(
                "아동 모형 배율: 지원 보정 ×"
                        + config.knobs().supportBoostScale()
                        + ", 난이도 보정 ×"
                        + config.knobs().levelPenaltyScale());
        notes.add("모든 아동·대화는 합성이다. 대화 문장 없이 답마다 학습 상태 하나만 만든다.");
        notes.add("지원 단계 규칙은 팀 역할극 원인 판단 프롬프트(prompts/roleplay/v2/cause-analysis.md)를 따른다.");
        notes.add("난이도별 시작 지원은 팀 최초 난이도 정책의 짝(L1·S2, L2·S1, L3·S0)을 후속 회차에도 쓴다고 가정했다.");
        notes.add("강사 선택은 규칙으로 흉내 냈다. 확장 추천이 있으면 그것, 없으면 추천 순서(보완→반복→적용)의 첫 번째.");
        notes.add("확장 후보 유사도는 글자 두 개 단위 벡터로 쟀다(실제 임베딩 아님). 그래서 기준을 0.2 이상·0.90 미만으로 낮췄다.");
        if (config.criteria()) {
            notes.add("답마다 학습 증거 기준별 관찰 상태(분명히·간접적으로·잘못 이해함)를 붙였다. 리포트 근거에 기준 설명이 들어간다.");
        }
        if (config.appropriateRuns() > 0) {
            notes.add(
                    "적정 난이도: 회차마다 그 아이의 지금 실력으로 L1~L3 를 "
                            + config.appropriateRuns()
                            + "번씩 미리 돌려, 평균 점수율이 50% 이상인 가장 높은 난이도로 정했다.");
        }

        EvalReportComposers llm = null;
        if (options.containsKey("report")) {
            llm =
                    EvalReportComposers.fromEnv(
                            Path.of(options.getOrDefault("env", ".env.local")),
                            options.getOrDefault("gen", "haiku55"),
                            options.getOrDefault("review", "argon4"));
            notes.add("리포트는 상세 시드에서만 실제 모델로 만들었다: " + llm.models());
        }
        try {
            RoleplaySimulation plain =
                    new RoleplaySimulation(spec, config, difficulty, recommendations, null);
            RoleplaySimulation withReports =
                    llm == null
                            ? plain
                            : new RoleplaySimulation(
                                    spec, config, difficulty, recommendations, llm.composer());
            Map<String, List<ChildRun>> runs = new LinkedHashMap<>();
            for (ChildProfile child : children) {
                List<ChildRun> childRuns = new ArrayList<>();
                for (long seed = 1; seed <= seeds; seed++) {
                    RoleplaySimulation simulation = seed == detailSeed ? withReports : plain;
                    childRuns.add(simulation.run(child, seed, sessions));
                }
                runs.put(child.key(), childRuns);
                System.out.println("  " + child.key() + " 완료");
            }
            if (llm != null) {
                Double cost = llm.costKrw();
                notes.add(
                        "LLM 호출 "
                                + llm.calls()
                                + "번 · 비용 "
                                + (cost == null
                                        ? "모름"
                                        : String.format(Locale.ROOT, "₩%.1f", cost)));
            }
            String report =
                    RoleplaySimReport.write(
                            runId, spec, runs, detailSeed, notes, similarityRows(spec, embedder));
            Path out = Path.of("build", "roleplay-sim", runId, "report.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report, StandardCharsets.UTF_8);
            System.out.println("보고서: " + out.toAbsolutePath());
        } finally {
            if (llm != null) llm.close();
        }
    }

    static RecommendationComposer recommendationComposer(
            SimSpec spec, CharBigramEmbedder embedder) {
        InMemoryGoalSimilaritySearch search = new InMemoryGoalSimilaritySearch(embedder);
        spec.learningGoals()
                .forEach(
                        goal -> search.index(RoleplaySimulation.CLASS_ID, goal.id(), goal.title()));
        return new RecommendationComposer(
                new RecommendationPolicy(RecommendationRules.V1),
                search,
                SIM_MIN_SIMILARITY,
                SIM_DUPLICATE_SIMILARITY);
    }

    static RoleplaySimulation.Config config(SimSpec spec, Map<String, String> options) {
        SimSpec.Session limits =
                new SimSpec.Session(
                        Integer.parseInt(
                                options.getOrDefault(
                                        "max-turns", String.valueOf(spec.session().maxTurns()))),
                        Integer.parseInt(
                                options.getOrDefault(
                                        "max-asks",
                                        String.valueOf(spec.session().maxSupportTurnsPerGoal()))));
        SimChild.Knobs knobs =
                new SimChild.Knobs(
                        Double.parseDouble(options.getOrDefault("boost-scale", "1.0")),
                        Double.parseDouble(options.getOrDefault("penalty-scale", "1.0")));
        return new RoleplaySimulation.Config(
                rules(options.getOrDefault("rules", "team")),
                limits,
                knobs,
                Integer.parseInt(options.getOrDefault("appropriate", "0")),
                Double.parseDouble(options.getOrDefault("appropriate-threshold", "0.5")),
                options.containsKey("criteria"));
    }

    /** v1 = 팀 확정값(50/80/연속 2). 나머지는 비교용 대안이다. */
    static FollowUpDifficultyRules difficultyRules(String name) {
        return switch (name) {
            case "v1" -> FollowUpDifficultyRules.V1;
            case "consecutive1" ->
                    new FollowUpDifficultyRules("SIM_CONSECUTIVE_1", 50, 80, 1, 2, null);
            case "consecutive3" ->
                    new FollowUpDifficultyRules("SIM_CONSECUTIVE_3", 50, 80, 3, 2, null);
            case "lower40" -> new FollowUpDifficultyRules("SIM_LOWER_40", 40, 80, 2, 2, null);
            case "upgrade70" -> new FollowUpDifficultyRules("SIM_UPGRADE_70", 50, 70, 2, 2, null);
            case "fast90" -> new FollowUpDifficultyRules("SIM_FAST_90", 50, 80, 2, 2, 90);
            default -> throw new IllegalArgumentException("unknown difficulty rules " + name);
        };
    }

    static List<String> similarityRows(SimSpec spec, CharBigramEmbedder embedder) {
        List<String> rows = new ArrayList<>();
        StringBuilder header = new StringBuilder("| |");
        StringBuilder line = new StringBuilder("|---|");
        for (LearningGoal goal : spec.learningGoals()) {
            header.append(' ').append(goal.key()).append(" |");
            line.append("---|");
        }
        rows.add(header.toString());
        rows.add(line.toString());
        Map<String, float[]> vectors = new HashMap<>();
        spec.learningGoals().forEach(goal -> vectors.put(goal.key(), embedder.embed(goal.title())));
        for (LearningGoal a : spec.learningGoals()) {
            StringBuilder row = new StringBuilder("| " + a.key() + " " + a.title() + " |");
            for (LearningGoal b : spec.learningGoals()) {
                row.append(' ')
                        .append(
                                String.format(
                                        Locale.ROOT,
                                        "%.2f",
                                        cosine(vectors.get(a.key()), vectors.get(b.key()))))
                        .append(" |");
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0, l = 0, r = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            l += left[i] * left[i];
            r += right[i] * right[i];
        }
        return l == 0 || r == 0 ? 0 : dot / Math.sqrt(l * r);
    }

    /** team = 팀 프롬프트 그대로, fix-unclear·fix-s3·fix-both = 실험으로 바꿔 본 규칙. */
    static SimRoleplayEngine.Rules rules(String name) {
        return switch (name) {
            case "team" -> SimRoleplayEngine.Rules.TEAM;
            case "fix-unclear" -> new SimRoleplayEngine.Rules(name, false, true);
            case "fix-s3" -> new SimRoleplayEngine.Rules(name, true, false);
            case "fix-both" -> new SimRoleplayEngine.Rules(name, false, false);
            default -> throw new IllegalArgumentException("unknown rules " + name);
        };
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            options.put(key, flag ? "true" : args[++i]);
        }
        return options;
    }
}
