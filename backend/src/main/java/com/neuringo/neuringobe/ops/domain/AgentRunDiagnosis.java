package com.neuringo.neuringobe.ops.domain;

import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Ending;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Finding;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Flow;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.StepStat;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Totals;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.WorkflowStat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 실행 기록 자동 진단. 이번 기간과 바로 앞 같은 길이 기간을 비교해, 사람이 먼저 볼 곳을 규칙으로 뽑는다. AI 를 부르지 않고 집계 값만 쓴다.
 *
 * <ul>
 *   <li>{@code FAILURE_RATE}: 실패(정상 결과 없이 끝남) 비율이 높거나 앞 기간보다 크게 올랐다.
 *   <li>{@code NEW_FAILURE_FLOW}: 앞 기간에 없던 실패 흐름 패턴이 나타났다.
 *   <li>{@code STEP_BLOCKED}: 한 호출 단계의 거절·실패 비율이 높다.
 *   <li>{@code REASON_SURGE}: 같은 원인·사유로 끝난 실행이 앞 기간보다 크게 늘었다.
 *   <li>{@code LATENCY}: 역할극 턴의 p95 가 턴 제한 시간에 가깝거나 앞 기간보다 크게 늘었다.
 * </ul>
 *
 * <p>표본이 적으면 판단하지 않는다(실행 {@value #MIN_RUNS}건·호출 {@value #MIN_CALLS}번 미만). 앞 기간 표본이 적으면 비교하는 규칙(새
 * 흐름·급증)은 건너뛴다. 기준값은 dev 관측 초기값이라, 실제 기록이 쌓이면 다시 맞춘다.
 */
public final class AgentRunDiagnosis {
    static final int MIN_RUNS = 20;
    static final int MIN_CALLS = 10;
    static final int MAX_FINDINGS = 8;
    static final double FAILURE_RATE_WARNING = 0.30;
    static final double FAILURE_RATE_CRITICAL = 0.50;
    static final double FAILURE_RISE_WARNING = 0.05;
    static final double FAILURE_RISE_CRITICAL = 0.15;
    static final double STEP_BLOCKED_WARNING = 0.20;
    static final double STEP_BLOCKED_CRITICAL = 0.40;
    static final int REASON_MIN_RUNS = 5;
    static final double REASON_SURGE_FACTOR = 2.0;
    static final double LATENCY_BUDGET_WARNING = 0.8;
    static final double LATENCY_RISE_FACTOR = 1.5;

    private AgentRunDiagnosis() {}

    public enum Severity {
        CRITICAL,
        WARNING,
        INFO
    }

    /** 한 기간의 집계. 요약 응답에 쓰는 것과 같은 값이다. */
    public record Snapshot(
            Totals totals,
            List<WorkflowStat> workflows,
            List<StepStat> steps,
            List<Ending> endings,
            List<Flow> flows) {}

    /**
     * @param turnBudgetMs 역할극 턴 제한 시간(roleplay.http.turn-budget)
     * @param slowestTurns 이번 기간에 가장 오래 걸린 역할극 턴 번호
     */
    public static List<Finding> diagnose(
            Snapshot now, Snapshot before, long turnBudgetMs, List<UUID> slowestTurns) {
        var findings = new ArrayList<Finding>();
        boolean comparable = before.totals().runs() >= MIN_RUNS;
        failureRate(now, before, comparable, findings);
        if (comparable) newFailureFlows(now, before, findings);
        stepBlocked(now, before, findings);
        if (comparable) reasonSurges(now, before, findings);
        latency(now, before, comparable, turnBudgetMs, slowestTurns, findings);
        findings.sort(
                Comparator.comparingInt((Finding f) -> Severity.valueOf(f.severity()).ordinal())
                        .thenComparingInt(f -> KINDS.indexOf(f.kind())));
        return List.copyOf(findings.subList(0, Math.min(MAX_FINDINGS, findings.size())));
    }

    private static final List<String> KINDS =
            List.of("FAILURE_RATE", "LATENCY", "STEP_BLOCKED", "NEW_FAILURE_FLOW", "REASON_SURGE");

    public static Double failureRate(Totals totals) {
        return totals.runs() == 0 ? null : (double) failures(totals) / totals.runs();
    }

    private static long failures(Totals totals) {
        return totals.guided() + totals.failed();
    }

    private static void failureRate(
            Snapshot now, Snapshot before, boolean comparable, List<Finding> findings) {
        if (now.totals().runs() < MIN_RUNS) return;
        double rate = failureRate(now.totals());
        Double previous = comparable ? failureRate(before.totals()) : null;
        double rise = previous == null ? 0 : rate - previous;
        Severity severity =
                rate >= FAILURE_RATE_CRITICAL || rise >= FAILURE_RISE_CRITICAL
                        ? Severity.CRITICAL
                        : rate >= FAILURE_RATE_WARNING || rise >= FAILURE_RISE_WARNING
                                ? Severity.WARNING
                                : null;
        if (severity == null) return;
        var samples =
                now.flows().stream()
                        .filter(flow -> "FAILURE".equals(flow.outcome()))
                        .flatMap(flow -> flow.recentRunIds().stream())
                        .limit(3)
                        .toList();
        findings.add(
                new Finding(
                        "FAILURE_RATE",
                        severity.name(),
                        null,
                        null,
                        null,
                        rate,
                        previous,
                        FAILURE_RATE_WARNING,
                        "RATE",
                        samples));
    }

    private static String signature(Flow flow) {
        var steps = new StringBuilder();
        flow.steps()
                .forEach(
                        s ->
                                steps.append(s.step())
                                        .append('|')
                                        .append(s.status())
                                        .append('|')
                                        .append(s.verdict())
                                        .append(','));
        return String.join("#", flow.workflow(), flow.path(), flow.result(), steps);
    }

    private static void newFailureFlows(Snapshot now, Snapshot before, List<Finding> findings) {
        Set<String> seen = new HashSet<>();
        before.flows().forEach(flow -> seen.add(signature(flow)));
        int added = 0;
        for (var flow : now.flows()) {
            if (added >= 3) break;
            if (!"FAILURE".equals(flow.outcome()) || seen.contains(signature(flow))) continue;
            var blocked =
                    flow.steps().stream()
                            .filter(step -> !"PASSED".equals(step.status()))
                            .findFirst()
                            .orElse(flow.steps().isEmpty() ? null : flow.steps().getLast());
            findings.add(
                    new Finding(
                            "NEW_FAILURE_FLOW",
                            ("FAILED".equals(flow.path()) ? Severity.WARNING : Severity.INFO)
                                    .name(),
                            flow.workflow(),
                            blocked == null ? null : blocked.step(),
                            blocked == null ? flow.result() : blocked.verdict(),
                            flow.count(),
                            0.0,
                            1,
                            "COUNT",
                            flow.recentRunIds()));
            added++;
        }
    }

    private static void stepBlocked(Snapshot now, Snapshot before, List<Finding> findings) {
        Map<String, StepStat> previous = new HashMap<>();
        before.steps().forEach(s -> previous.put(s.workflow() + "|" + s.step(), s));
        for (var step : now.steps()) {
            if (step.calls() < MIN_CALLS) continue;
            double rate = (double) (step.rejected() + step.failed()) / step.calls();
            Severity severity =
                    rate >= STEP_BLOCKED_CRITICAL
                            ? Severity.CRITICAL
                            : rate >= STEP_BLOCKED_WARNING ? Severity.WARNING : null;
            if (severity == null) continue;
            var earlier = previous.get(step.workflow() + "|" + step.step());
            Double earlierRate =
                    earlier == null || earlier.calls() < MIN_CALLS
                            ? null
                            : (double) (earlier.rejected() + earlier.failed()) / earlier.calls();
            // 이 단계에서 가장 많이 나온 거절·실패 판정과, 그 판정이 들어 있는 흐름의 최근 실행
            Map<String, Long> verdicts = new LinkedHashMap<>();
            Set<UUID> samples = new LinkedHashSet<>();
            for (var flow : now.flows()) {
                if (!flow.workflow().equals(step.workflow())) continue;
                for (var s : flow.steps())
                    if (s.step().equals(step.step()) && !"PASSED".equals(s.status())) {
                        verdicts.merge(Objects.toString(s.verdict(), ""), flow.count(), Long::sum);
                        flow.recentRunIds().stream()
                                .limit(3 - Math.min(3, samples.size()))
                                .forEach(samples::add);
                    }
            }
            String top =
                    verdicts.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .filter(code -> !code.isEmpty())
                            .orElse(null);
            findings.add(
                    new Finding(
                            "STEP_BLOCKED",
                            severity.name(),
                            step.workflow(),
                            step.step(),
                            top,
                            rate,
                            earlierRate,
                            STEP_BLOCKED_WARNING,
                            "RATE",
                            List.copyOf(samples).subList(0, Math.min(3, samples.size()))));
        }
    }

    private record ReasonKey(String workflow, String endedBy, String reason) {}

    private static Map<ReasonKey, List<Ending>> byReason(List<Ending> endings) {
        Map<ReasonKey, List<Ending>> grouped = new LinkedHashMap<>();
        for (var ending : endings)
            if (!ending.reason().isEmpty())
                grouped.computeIfAbsent(
                                new ReasonKey(ending.workflow(), ending.endedBy(), ending.reason()),
                                key -> new ArrayList<>())
                        .add(ending);
        return grouped;
    }

    private static void reasonSurges(Snapshot now, Snapshot before, List<Finding> findings) {
        var previous = byReason(before.endings());
        for (var entry : byReason(now.endings()).entrySet()) {
            var key = entry.getKey();
            long count = entry.getValue().stream().mapToLong(Ending::count).sum();
            if (count < REASON_MIN_RUNS) continue;
            long earlier =
                    previous.getOrDefault(key, List.of()).stream().mapToLong(Ending::count).sum();
            double rate = (double) count / now.totals().runs();
            double earlierRate = (double) earlier / before.totals().runs();
            if (earlier > 0 && rate < earlierRate * REASON_SURGE_FACTOR) continue;
            // 같은 단계·판정을 단계 진단이 이미 짚었으면 겹쳐 내지 않는다.
            boolean covered =
                    findings.stream()
                            .anyMatch(
                                    f ->
                                            Objects.equals(f.workflow(), key.workflow())
                                                    && Objects.equals(f.step(), key.endedBy())
                                                    && Objects.equals(f.code(), key.reason()));
            if (covered) continue;
            boolean failure =
                    entry.getValue().stream()
                            .anyMatch(e -> "GUIDED".equals(e.path()) || "FAILED".equals(e.path()));
            findings.add(
                    new Finding(
                            "REASON_SURGE",
                            (failure ? Severity.WARNING : Severity.INFO).name(),
                            key.workflow(),
                            key.endedBy().isEmpty() ? null : key.endedBy(),
                            key.reason(),
                            count,
                            (double) earlier,
                            REASON_SURGE_FACTOR,
                            "COUNT",
                            entry.getValue().stream()
                                    .flatMap(e -> e.recentRunIds().stream())
                                    .limit(3)
                                    .toList()));
        }
    }

    private static void latency(
            Snapshot now,
            Snapshot before,
            boolean comparable,
            long turnBudgetMs,
            List<UUID> slowestTurns,
            List<Finding> findings) {
        var current = roleplay(now.workflows());
        if (current == null || current.runs() < MIN_RUNS || turnBudgetMs <= 0) return;
        var earlier = comparable ? roleplay(before.workflows()) : null;
        Double previous =
                earlier == null || earlier.runs() < MIN_RUNS ? null : (double) earlier.p95Ms();
        double p95 = current.p95Ms();
        Severity severity =
                p95 >= turnBudgetMs
                        ? Severity.CRITICAL
                        : p95 >= turnBudgetMs * LATENCY_BUDGET_WARNING
                                        || (previous != null
                                                && p95 >= previous * LATENCY_RISE_FACTOR
                                                && p95 >= turnBudgetMs * 0.5)
                                ? Severity.WARNING
                                : null;
        if (severity == null) return;
        findings.add(
                new Finding(
                        "LATENCY",
                        severity.name(),
                        current.workflow(),
                        null,
                        null,
                        p95,
                        previous,
                        turnBudgetMs * LATENCY_BUDGET_WARNING,
                        "MS",
                        slowestTurns.stream().limit(3).toList()));
    }

    private static WorkflowStat roleplay(List<WorkflowStat> workflows) {
        return workflows.stream()
                .filter(w -> "ROLEPLAY_TURN".equals(w.workflow()))
                .findFirst()
                .orElse(null);
    }
}
