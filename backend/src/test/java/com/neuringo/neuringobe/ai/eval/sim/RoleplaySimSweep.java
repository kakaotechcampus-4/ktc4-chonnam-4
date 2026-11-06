package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.ai.eval.sim.RoleplaySimulation.ChildRun;
import com.neuringo.neuringobe.ai.eval.sim.SimSpec.ChildProfile;
import com.neuringo.neuringobe.report.application.RecommendationComposer;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyPolicy;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 여러 설정을 한 번에 돌려 표 하나로 비교한다.
 *
 * <ul>
 *   <li>{@code --sweep assumptions}: 시뮬레이션이 정한 가정(턴 상한, 묻는 횟수 상한, 아동 모형 보정 배율)을 바꿔도 앞선 두 발견(S3 고착,
 *       판단 불가 누수)이 유지되는지 본다.
 *   <li>{@code --sweep difficulty}: 난이도 규칙 대안과 역할극 규칙을 바꿔 적정 난이도에 있던 회차 비율과 오르내림을 비교한다.
 * </ul>
 */
final class RoleplaySimSweep {

    /** 적정 난이도 기준 점수율. --appropriate-threshold 로 바꾼다. */
    private static double THRESHOLD = 0.5;

    private RoleplaySimSweep() {}

    static void run(SimSpec spec, Map<String, String> options) throws IOException {
        String mode = options.get("sweep");
        int seeds = Integer.parseInt(options.getOrDefault("seeds", "20"));
        int sessions = Integer.parseInt(options.getOrDefault("sessions", "8"));
        String runId = options.getOrDefault("run-id", "sweep-" + mode);
        THRESHOLD = Double.parseDouble(options.getOrDefault("appropriate-threshold", "0.5"));
        String body =
                switch (mode) {
                    case "assumptions" -> assumptions(spec, seeds, sessions);
                    case "difficulty" ->
                            difficulty(
                                    spec,
                                    seeds,
                                    sessions,
                                    Integer.parseInt(options.getOrDefault("appropriate", "40")));
                    default -> throw new IllegalArgumentException("unknown sweep " + mode);
                };
        Path out = Path.of("build", "roleplay-sim", runId, "sweep.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, body, StandardCharsets.UTF_8);
        System.out.println("보고서: " + out.toAbsolutePath());
    }

    private static String assumptions(SimSpec spec, int seeds, int sessions) {
        RecommendationComposer recs =
                RoleplaySimMain.recommendationComposer(spec, new CharBigramEmbedder());
        FollowUpDifficultyPolicy difficulty =
                new FollowUpDifficultyPolicy(FollowUpDifficultyRules.V1);
        int[] maxTurns = {8, 12, 16};
        int[] maxAsks = {2, 3, 4};
        double[] boosts = {0.75, 1.0, 1.25};
        double[] penalties = {0.5, 1.0, 1.5};
        StringBuilder out = new StringBuilder();
        out.append("# 가정 민감도 — 턴 상한·묻는 횟수 상한·아동 모형 보정을 바꿔도 발견이 유지되나\n\n");
        out.append("조합 ")
                .append(maxTurns.length * maxAsks.length * boosts.length * penalties.length)
                .append("개, 아동별 시드 ")
                .append(seeds)
                .append("개 × ")
                .append(sessions)
                .append("회차.\n\n");
        out.append("- 발견 1(S3 고착): 팀 규칙에서 fix-s3 로 바꾸면 C3 뒤집힘이 줄어드는가\n");
        out.append("- 발견 2(판단 불가 누수): 팀 규칙에서 C4 의 L3 비율이 C4N 보다 낮고, fix-unclear 로 차이가 줄어드는가\n\n");
        out.append(
                "| 턴 | 묻기 | 지원 보정 | 난이도 보정 | C3 뒤집힘 팀→fix-s3 | C1 뒤집힘 팀→fix-s3 | C4N−C4 L3 차이 팀→fix-unclear | 발견 1 | 발견 2 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        int combos = 0, holds1 = 0, holds2 = 0;
        List<Double> drop1 = new ArrayList<>();
        List<Double> gapTeam = new ArrayList<>();
        List<Double> gapFixed = new ArrayList<>();
        for (int turns : maxTurns) {
            for (int asks : maxAsks) {
                for (double boost : boosts) {
                    for (double penalty : penalties) {
                        SimSpec.Session limits = new SimSpec.Session(turns, asks);
                        SimChild.Knobs knobs = new SimChild.Knobs(boost, penalty);
                        SimMetrics c3Team =
                                metrics(
                                        spec,
                                        "C3",
                                        "team",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        SimMetrics c3Fix =
                                metrics(
                                        spec,
                                        "C3",
                                        "fix-s3",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        SimMetrics c1Team =
                                metrics(
                                        spec,
                                        "C1",
                                        "team",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        SimMetrics c1Fix =
                                metrics(
                                        spec,
                                        "C1",
                                        "fix-s3",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        SimMetrics c4Team =
                                metrics(
                                        spec,
                                        "C4",
                                        "team",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        SimMetrics c4nTeam =
                                metrics(
                                        spec,
                                        "C4N",
                                        "team",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        SimMetrics c4Fix =
                                metrics(
                                        spec,
                                        "C4",
                                        "fix-unclear",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        SimMetrics c4nFix =
                                metrics(
                                        spec,
                                        "C4N",
                                        "fix-unclear",
                                        limits,
                                        knobs,
                                        seeds,
                                        sessions,
                                        difficulty,
                                        recs,
                                        0);
                        double gap = c4nTeam.finalL3() - c4Team.finalL3();
                        double gapAfter = c4nFix.finalL3() - c4Fix.finalL3();
                        boolean f1 =
                                c3Fix.flips() < c3Team.flips() && c1Fix.flips() <= c1Team.flips();
                        boolean f2 = gap > 0 && gapAfter < gap;
                        combos++;
                        if (f1) holds1++;
                        if (f2) holds2++;
                        drop1.add(c3Team.flips() - c3Fix.flips());
                        gapTeam.add(gap);
                        gapFixed.add(gapAfter);
                        out.append("| ")
                                .append(turns)
                                .append(" | ")
                                .append(asks)
                                .append(" | ×")
                                .append(boost)
                                .append(" | ×")
                                .append(penalty)
                                .append(" | ")
                                .append(num(c3Team.flips()))
                                .append(" → ")
                                .append(num(c3Fix.flips()))
                                .append(" | ")
                                .append(num(c1Team.flips()))
                                .append(" → ")
                                .append(num(c1Fix.flips()))
                                .append(" | ")
                                .append(pct(gap))
                                .append(" → ")
                                .append(pct(gapAfter))
                                .append(" | ")
                                .append(f1 ? "✅" : "❌")
                                .append(" | ")
                                .append(f2 ? "✅" : "❌")
                                .append(" |\n");
                    }
                }
            }
            System.out.println("  턴 상한 " + turns + " 완료");
        }
        out.insert(
                out.indexOf("| 턴 |"),
                "결과: 발견 1 은 "
                        + holds1
                        + "/"
                        + combos
                        + " 조합, 발견 2 는 "
                        + holds2
                        + "/"
                        + combos
                        + " 조합에서 유지됐다. "
                        + "C3 뒤집힘 감소 중앙값 "
                        + num(median(drop1))
                        + "번, C4N−C4 L3 차이 중앙값 팀 규칙 "
                        + pct(median(gapTeam))
                        + " → fix-unclear "
                        + pct(median(gapFixed))
                        + ".\n\n");
        return out.toString();
    }

    private static String difficulty(SimSpec spec, int seeds, int sessions, int appropriateRuns) {
        RecommendationComposer recs =
                RoleplaySimMain.recommendationComposer(spec, new CharBigramEmbedder());
        String[] difficultyRules = {
            "v1", "consecutive1", "consecutive3", "lower40", "upgrade70", "fast90"
        };
        String[] roleplayRules = {"team", "fix-both"};
        StringBuilder out = new StringBuilder();
        out.append("# 난이도 규칙 비교 — 적정 난이도에 얼마나 있었나\n\n");
        out.append("적정 난이도는 회차마다 그 아이의 지금 실력으로 L1~L3 를 ")
                .append(appropriateRuns)
                .append("번씩 미리 돌려, 평균 점수율이 ")
                .append(Math.round(THRESHOLD * 100))
                .append("% 이상인 가장 높은 난이도다. 아동 7명 × 시드 ")
                .append(seeds)
                .append("개 × ")
                .append(sessions)
                .append("회차를 합친 값이다.\n\n");
        out.append(
                "| 역할극 규칙 | 난이도 규칙 | 적정 | 너무 어려움 | 너무 쉬움 | 하향/시드 | 뒤집힘/시드 |\n|---|---|---|---|---|---|---|\n");
        Map<String, Map<String, SimMetrics>> perChild = new LinkedHashMap<>();
        for (String roleplay : roleplayRules) {
            for (String rulesName : difficultyRules) {
                FollowUpDifficultyPolicy policy =
                        new FollowUpDifficultyPolicy(RoleplaySimMain.difficultyRules(rulesName));
                List<ChildRun> all = new ArrayList<>();
                for (ChildProfile child : spec.children()) {
                    List<ChildRun> runs =
                            runs(
                                    spec,
                                    child,
                                    roleplay,
                                    spec.session(),
                                    SimChild.Knobs.DEFAULT,
                                    seeds,
                                    sessions,
                                    policy,
                                    recs,
                                    appropriateRuns);
                    all.addAll(runs);
                    perChild.computeIfAbsent(
                                    roleplay + " · " + rulesName, k -> new LinkedHashMap<>())
                            .put(child.key(), SimMetrics.of(runs));
                }
                SimMetrics m = SimMetrics.of(all);
                out.append("| ")
                        .append(roleplay)
                        .append(" | ")
                        .append(rulesName)
                        .append(" | ")
                        .append(pct(m.atAppropriate()))
                        .append(" | ")
                        .append(pct(m.tooHard()))
                        .append(" | ")
                        .append(pct(m.tooEasy()))
                        .append(" | ")
                        .append(num(m.lowers()))
                        .append(" | ")
                        .append(num(m.flips()))
                        .append(" |\n");
                System.out.println("  " + roleplay + " · " + rulesName + " 완료");
            }
        }
        out.append("\n## 아동별 적정 비율\n\n| 설정 |");
        spec.children().forEach(c -> out.append(' ').append(c.key()).append(" |"));
        out.append("\n|---|").append("---|".repeat(spec.children().size())).append('\n');
        perChild.forEach(
                (name, byChild) -> {
                    out.append("| ").append(name).append(" |");
                    byChild.values()
                            .forEach(
                                    m ->
                                            out.append(' ')
                                                    .append(pct(m.atAppropriate()))
                                                    .append(" |"));
                    out.append('\n');
                });
        return out.toString();
    }

    private static SimMetrics metrics(
            SimSpec spec,
            String child,
            String rules,
            SimSpec.Session limits,
            SimChild.Knobs knobs,
            int seeds,
            int sessions,
            FollowUpDifficultyPolicy difficulty,
            RecommendationComposer recs,
            int appropriateRuns) {
        return SimMetrics.of(
                runs(
                        spec,
                        spec.child(child),
                        rules,
                        limits,
                        knobs,
                        seeds,
                        sessions,
                        difficulty,
                        recs,
                        appropriateRuns));
    }

    private static List<ChildRun> runs(
            SimSpec spec,
            ChildProfile child,
            String rules,
            SimSpec.Session limits,
            SimChild.Knobs knobs,
            int seeds,
            int sessions,
            FollowUpDifficultyPolicy difficulty,
            RecommendationComposer recs,
            int appropriateRuns) {
        double threshold = THRESHOLD;
        RoleplaySimulation simulation =
                new RoleplaySimulation(
                        spec,
                        new RoleplaySimulation.Config(
                                RoleplaySimMain.rules(rules),
                                limits,
                                knobs,
                                appropriateRuns,
                                threshold,
                                false),
                        difficulty,
                        recs,
                        null);
        List<ChildRun> runs = new ArrayList<>();
        for (long seed = 1; seed <= seeds; seed++) {
            runs.add(simulation.run(child, seed, sessions));
        }
        return runs;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String pct(double value) {
        return Double.isNaN(value) ? "-" : Math.round(value * 100) + "%";
    }
}
