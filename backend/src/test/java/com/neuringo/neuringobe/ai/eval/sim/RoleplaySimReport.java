package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.ai.application.report.LearningReportResult;
import com.neuringo.neuringobe.ai.eval.sim.RoleplaySimulation.ChildRun;
import com.neuringo.neuringobe.ai.eval.sim.RoleplaySimulation.Session;
import com.neuringo.neuringobe.ai.eval.sim.SimSpec.ChildProfile;
import com.neuringo.neuringobe.report.application.Recommendation;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Direction;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Reason;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 시뮬레이션 결과를 마크다운 한 장으로 쓴다. 모든 값은 합성 아동의 것이다. */
final class RoleplaySimReport {

    private RoleplaySimReport() {}

    static String write(
            String runId,
            SimSpec spec,
            Map<String, List<ChildRun>> runsByChild,
            long detailSeed,
            List<String> notes,
            List<String> similarityRows) {
        StringBuilder out = new StringBuilder();
        int sessions = runsByChild.values().iterator().next().getFirst().sessions().size();
        int seeds = runsByChild.values().iterator().next().size();
        out.append("# 역할극 다회차 시뮬레이션 — ").append(runId).append("\n\n");
        out.append("| 항목 | 값 |\n|---|---|\n");
        out.append("| 설정 | `").append(spec.version()).append("` |\n");
        out.append("| 아동 프로필 | ").append(runsByChild.size()).append("명 |\n");
        out.append("| 회차 | 아동마다 ").append(sessions).append("회 |\n");
        out.append("| 시드 | ").append(seeds).append("개(1~").append(seeds).append(") |\n");
        out.append("| 턴 상한 | 역할극당 ")
                .append(spec.session().maxTurns())
                .append(", 작은 목표당 ")
                .append(spec.session().maxSupportTurnsPerGoal())
                .append("번 묻고 나면 S3 |\n");
        out.append("| 상세 표 시드 | ").append(detailSeed).append(" |\n\n");
        notes.forEach(note -> out.append("- ").append(note).append('\n'));
        out.append('\n');

        out.append("## 요약 (시드 평균)\n\n");
        out.append(
                "| 아동 | 처음 | 마지막 회차 난이도 L1 / L2 / L3 | 상향 | 하향 | 뒤집힘 | 첫 L3 회차(중앙값) | 근거 부족 회차 | 제외 목표 | 확장 선택 | 평균 턴 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        runsByChild.forEach((key, runs) -> out.append(summaryRow(runs)).append('\n'));
        out.append("\n뒤집힘은 상향 바로 다음 결정이 하향이거나 그 반대인 횟수다. 근거 부족은 유효 목표가 2개 미만이라 난이도를 유지한 회차다.\n\n");
        boolean measured =
                runsByChild.values().stream()
                        .flatMap(List::stream)
                        .flatMap(run -> run.sessions().stream())
                        .anyMatch(s -> s.appropriate() != null);
        if (measured) {
            out.append("## 적정 난이도에 있던 회차\n\n| 아동 | 적정 | 너무 어려움 | 너무 쉬움 |\n|---|---|---|---|\n");
            runsByChild.forEach(
                    (key, runs) -> {
                        SimMetrics m = SimMetrics.of(runs);
                        out.append("| ")
                                .append(key)
                                .append(" | ")
                                .append(pct((int) Math.round(m.atAppropriate() * 1000), 1000))
                                .append(" | ")
                                .append(pct((int) Math.round(m.tooHard() * 1000), 1000))
                                .append(" | ")
                                .append(pct((int) Math.round(m.tooEasy() * 1000), 1000))
                                .append(" |\n");
                    });
            out.append('\n');
        }

        out.append("## 추천 유형이 나온 비율 (시드·회차 전체)\n\n");
        out.append("| 아동 | 보완 | 반복 | 적용 | 확장 | 근거 부족(반복만) |\n|---|---|---|---|---|---|\n");
        runsByChild.forEach((key, runs) -> out.append(recommendationRow(runs)).append('\n'));
        out.append('\n');

        out.append("## 회차별 평균 점수율·난이도 (시드 평균)\n\n");
        out.append("| 아동 |");
        for (int i = 1; i <= sessions; i++) out.append(' ').append(i).append("회 |");
        out.append("\n|---|");
        out.append("---|".repeat(sessions)).append('\n');
        runsByChild.forEach((key, runs) -> out.append(trajectoryRow(runs, sessions)).append('\n'));
        out.append("\n칸은 `점수율 · 평균 난이도`다. 평균 난이도는 L1=1, L2=2, L3=3 의 평균이다.\n\n");

        out.append("## 회차별 상세 (시드 ").append(detailSeed).append(")\n\n");
        runsByChild.forEach(
                (key, runs) ->
                        runs.stream()
                                .filter(run -> run.seed() == detailSeed)
                                .findFirst()
                                .ifPresent(run -> out.append(detail(run))));

        if (!similarityRows.isEmpty()) {
            out.append("## 학습 목표 사이 유사도 (확장 추천 후보)\n\n");
            similarityRows.forEach(row -> out.append(row).append('\n'));
            out.append('\n');
        }
        return out.toString();
    }

    private static String summaryRow(List<ChildRun> runs) {
        ChildProfile child = runs.getFirst().child();
        int[] finalLevels = new int[3];
        double raises = 0,
                lowers = 0,
                flips = 0,
                insufficient = 0,
                excluded = 0,
                extends_ = 0,
                turns = 0;
        int goals = 0, sessions = 0;
        List<Integer> firstL3 = new ArrayList<>();
        for (ChildRun run : runs) {
            Direction last = null;
            Integer reachedL3 = null;
            for (Session s : run.sessions()) {
                sessions++;
                turns += s.turns().size();
                Direction d = s.decision().direction();
                if (d == Direction.RAISE) raises++;
                if (d == Direction.LOWER) lowers++;
                if ((d == Direction.RAISE && last == Direction.LOWER)
                        || (d == Direction.LOWER && last == Direction.RAISE)) {
                    flips++;
                }
                if (d != Direction.KEEP) last = d;
                if (s.decision().reason() == Reason.INSUFFICIENT_VALID_GOALS) insufficient++;
                for (GoalOutcome o : s.summary().outcomes()) {
                    goals++;
                    if (!o.valid()) excluded++;
                }
                if (s.chosen() != null
                        && s.chosen().candidate().type() == RecommendationType.EXTEND) extends_++;
                if (reachedL3 == null && s.decision().nextLevel() == ScenarioLevel.L3)
                    reachedL3 = s.number() + 1;
            }
            finalLevels[run.sessions().getLast().decision().nextLevel().ordinal()]++;
            if (reachedL3 != null) firstL3.add(reachedL3);
        }
        int n = runs.size();
        return "| "
                + child.key()
                + " "
                + child.label()
                + " | "
                + child.initialLevel()
                + " | "
                + pct(finalLevels[0], n)
                + " / "
                + pct(finalLevels[1], n)
                + " / "
                + pct(finalLevels[2], n)
                + " | "
                + num(raises / n)
                + " | "
                + num(lowers / n)
                + " | "
                + num(flips / n)
                + " | "
                + (firstL3.isEmpty() ? "-" : median(firstL3) + "회 (" + pct(firstL3.size(), n) + ")")
                + " | "
                + pct((int) insufficient, sessions)
                + " | "
                + pct((int) excluded, goals)
                + " | "
                + num(extends_ / n)
                + " | "
                + num(turns / sessions)
                + " |";
    }

    private static String recommendationRow(List<ChildRun> runs) {
        Map<RecommendationType, Integer> seen = new EnumMap<>(RecommendationType.class);
        int sessions = 0, notEnough = 0;
        for (ChildRun run : runs) {
            for (Session s : run.sessions()) {
                sessions++;
                for (Recommendation r : s.recommendations()) {
                    seen.merge(r.candidate().type(), 1, Integer::sum);
                    if (r.candidate().reason().name().equals("NOT_ENOUGH_EVIDENCE")) notEnough++;
                }
            }
        }
        int total = sessions;
        return "| "
                + runs.getFirst().child().key()
                + " | "
                + pct(seen.getOrDefault(RecommendationType.REINFORCE, 0), total)
                + " | "
                + pct(seen.getOrDefault(RecommendationType.REPEAT, 0), total)
                + " | "
                + pct(seen.getOrDefault(RecommendationType.APPLY, 0), total)
                + " | "
                + pct(seen.getOrDefault(RecommendationType.EXTEND, 0), total)
                + " | "
                + pct(notEnough, total)
                + " |";
    }

    private static String trajectoryRow(List<ChildRun> runs, int sessions) {
        StringBuilder row = new StringBuilder("| " + runs.getFirst().child().key() + " |");
        for (int i = 0; i < sessions; i++) {
            double score = 0, level = 0;
            int scored = 0;
            for (ChildRun run : runs) {
                Session s = run.sessions().get(i);
                level += s.level().ordinal() + 1;
                if (s.decision().scoreRate().goalCount() > 0) {
                    score += s.decision().scoreRate().ratio();
                    scored++;
                }
            }
            row.append(' ')
                    .append(scored == 0 ? "-" : Math.round(score / scored * 100) + "%")
                    .append(" · ")
                    .append(num(level / runs.size()))
                    .append(" |");
        }
        return row.toString();
    }

    private static String detail(ChildRun run) {
        StringBuilder out = new StringBuilder();
        ChildProfile child = run.child();
        out.append("### ").append(child.key()).append(" — ").append(child.label()).append("\n\n");
        out.append("확인하려는 것: ").append(child.purpose()).append("\n\n");
        out.append("| 회차 | 학습 목표 | 난이도·시작 | 작은 목표별 결과 (시작→최종) | 점수율 | 다음 난이도 | 추천 | 강사 선택 | 턴 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Session s : run.sessions()) {
            out.append("| ")
                    .append(s.number())
                    .append(" | ")
                    .append(s.goal().key())
                    .append(' ')
                    .append(s.goal().title())
                    .append(" | ")
                    .append(s.level())
                    .append('·')
                    .append(s.startSupport())
                    .append(" | ")
                    .append(goals(s))
                    .append(" | ")
                    .append(
                            s.decision().scoreRate().goalCount() == 0
                                    ? "-"
                                    : Math.round(s.decision().scoreRate().ratio() * 100) + "%")
                    .append(" | ")
                    .append(s.decision().nextLevel())
                    .append(' ')
                    .append(s.decision().direction())
                    .append(" `")
                    .append(s.decision().reason())
                    .append('`')
                    .append(" | ")
                    .append(recs(s.recommendations()))
                    .append(" | ")
                    .append(s.chosen() == null ? "-" : label(s.chosen()))
                    .append(" | ")
                    .append(s.turns().size())
                    .append(s.endReason() == SimRoleplayEngine.EndReason.TURN_LIMIT ? " (상한)" : "")
                    .append(" |\n");
        }
        out.append('\n');
        boolean anyReport = run.sessions().stream().anyMatch(s -> s.report() != null);
        if (anyReport) {
            out.append("리포트(LLM)\n\n");
            for (Session s : run.sessions()) {
                if (s.report() == null) continue;
                out.append("- ").append(s.number()).append("회: ");
                if (s.report().result() instanceof LearningReportResult.Completed done) {
                    out.append(done.rounds().size())
                            .append("바퀴 통과 — ")
                            .append(done.draft().summary());
                    done.draft()
                            .observations()
                            .forEach(i -> out.append("\n  - 관찰: ").append(i.text()));
                    done.draft()
                            .effectiveSupports()
                            .forEach(i -> out.append("\n  - 효과적인 지원: ").append(i.text()));
                    done.draft()
                            .difficultPoints()
                            .forEach(i -> out.append("\n  - 어려운 지점: ").append(i.text()));
                } else if (s.report().result() instanceof LearningReportResult.Failed failed) {
                    out.append("실패 `").append(failed.reason()).append('`');
                }
                out.append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String goals(Session s) {
        List<String> parts = new ArrayList<>();
        List<GoalEvidence> goals = s.summary().goals();
        for (GoalEvidence g : goals) {
            GoalOutcome o = g.outcome();
            String mark =
                    switch (o.result()) {
                        case REACHED -> o.reachedWithoutAnswer() ? "✓" : "✓(답)";
                        case NOT_REACHED -> "✗";
                        case EXCLUDED_TECHNICAL_ERROR -> "기술";
                        case EXCLUDED_INTERRUPTED -> "못 함";
                        case EXCLUDED_REFUSED -> "거부";
                    };
            parts.add(
                    g.goalTitle().split(" ")[0]
                            + " "
                            + o.startSupport()
                            + "→"
                            + o.finalSupport()
                            + " "
                            + mark);
        }
        return String.join(", ", parts);
    }

    private static String recs(List<Recommendation> recs) {
        return recs.isEmpty()
                ? "-"
                : String.join(", ", recs.stream().map(RoleplaySimReport::label).toList());
    }

    private static String label(Recommendation r) {
        String type =
                switch (r.candidate().type()) {
                    case REINFORCE -> "보완";
                    case REPEAT -> "반복";
                    case APPLY -> "적용";
                    case EXTEND -> "확장";
                };
        if (r.candidate().type() == RecommendationType.EXTEND) {
            return r.suggestedGoal() == null
                    ? type + "(후보 없음)"
                    : type + "→" + r.suggestedGoal().title();
        }
        return r.candidate().basis() == null
                ? type + "(근거 부족)"
                : type + "(" + r.candidate().basis().goalTitle().split(" ")[0] + ")";
    }

    private static String pct(int count, int total) {
        return total == 0 ? "-" : Math.round(count * 100.0 / total) + "%";
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int median(List<Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
