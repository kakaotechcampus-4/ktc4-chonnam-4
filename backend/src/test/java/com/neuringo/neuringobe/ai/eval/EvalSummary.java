package com.neuringo.neuringobe.ai.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 실행 한 번(모델 하나 × 반복 N회)의 집계. summary.json 이 되고, 여러 개를 모아 모델 비교표를 만든다. */
record EvalSummary(
        Run run,
        int turns,
        Rate delivered,
        Rate firstEvaluationPass,
        Rate recovery,
        Rate timedOut,
        Latency turnLatency,
        Map<String, Latency> stageLatency,
        double llmCallsPerTurn,
        double inputTokensPerTurn,
        double outputTokensPerTurn,
        Double costPerTurnUsd,
        Double costTotalUsd,
        Rate formatFailure,
        Map<String, Integer> formatFailuresByOperation,
        Map<String, Integer> formatFailureReasons,
        Map<String, Integer> providerFailures,
        Map<String, Integer> evaluationDecisions,
        Map<String, Integer> failureCodes,
        Rate expectation,
        Map<String, Rate> expectationByCheck,
        List<CaseRow> cases) {

    /** 실행 조건. 결과를 다시 볼 때 무엇으로 쟀는지 알 수 있게 남긴다. */
    record Run(
            String runId,
            String setId,
            String provider,
            String model,
            String startedAt,
            String gitCommit,
            int repeats,
            long turnBudgetMs,
            String promptVersions,
            Double priceInputPerMTok,
            Double priceOutputPerMTok,
            String note) {}

    record Rate(int count, int total) {
        double ratio() {
            return total == 0 ? 0.0 : (double) count / total;
        }

        String percent() {
            return total == 0 ? "-" : "%.1f%% (%d/%d)".formatted(ratio() * 100, count, total);
        }
    }

    record Latency(long p50, long p95, long max, int samples) {
        static Latency of(Collection<Long> values) {
            List<Long> sorted = values.stream().sorted().toList();
            if (sorted.isEmpty()) return new Latency(0, 0, 0, 0);
            return new Latency(
                    percentile(sorted, 50),
                    percentile(sorted, 95),
                    sorted.getLast(),
                    sorted.size());
        }

        /** 제한 시간 제안: max(p95 × 2, 최대 × 1.5) 를 초 단위로 올림. 꼬리 지연 한 번에 재시도가 나지 않을 만큼 둔다. */
        String suggestedTimeout() {
            if (samples == 0) return "-";
            double millis = Math.max(p95 * 2.0, max * 1.5);
            return (long) Math.ceil(millis / 1000.0) + "s";
        }

        /** nearest-rank 백분위. 표본이 적을 때 보간 없이 실제 측정값 하나를 고른다. */
        private static long percentile(List<Long> sorted, int p) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.size());
            return sorted.get(Math.max(0, rank - 1));
        }
    }

    record CaseRow(
            String caseId,
            String suite,
            int runs,
            int delivered,
            int firstEvaluationPass,
            int checksPassed,
            int checksTotal,
            long p50Ms,
            List<String> mismatches) {}

    static EvalSummary of(Run run, List<TurnRecord> records) {
        int turns = records.size();
        List<TurnRecord.StageCall> calls =
                records.stream().flatMap(r -> r.calls().stream()).toList();
        int providerSuccesses =
                (int) calls.stream().filter(c -> c.providerFailure() == null).count();
        List<TurnRecord.FormatFailureSummary> formatFailures =
                records.stream().flatMap(r -> r.formatFailures().stream()).toList();

        Map<String, Latency> stageLatency = new LinkedHashMap<>();
        calls.stream()
                .collect(
                        Collectors.groupingBy(
                                TurnRecord.StageCall::operation,
                                TreeMap::new,
                                Collectors.mapping(
                                        TurnRecord.StageCall::wallMs, Collectors.toList())))
                .forEach((operation, values) -> stageLatency.put(operation, Latency.of(values)));

        long inputTokens = records.stream().mapToLong(TurnRecord::inputTokens).sum();
        long outputTokens = records.stream().mapToLong(TurnRecord::outputTokens).sum();
        Double costTotal =
                run.priceInputPerMTok() == null || run.priceOutputPerMTok() == null
                        ? null
                        : (inputTokens * run.priceInputPerMTok()
                                        + outputTokens * run.priceOutputPerMTok())
                                / 1_000_000.0;

        List<TurnRecord.Check> checks = records.stream().flatMap(r -> r.checks().stream()).toList();
        Map<String, Rate> byCheck = new TreeMap<>();
        checks.stream()
                .filter(c -> !"NOT_EVALUATED".equals(c.status()))
                .collect(Collectors.groupingBy(TurnRecord.Check::name))
                .forEach(
                        (name, list) ->
                                byCheck.put(
                                        name,
                                        new Rate(
                                                (int)
                                                        list.stream()
                                                                .filter(TurnRecord.Check::passed)
                                                                .count(),
                                                list.size())));
        // 비교하지 못한 항목(분석·응답이 없음)은 실패로 센다. 복구로 끝난 턴이 기대값 통과율을 부풀리지 않게 한다.
        Rate expectation =
                new Rate(
                        (int) checks.stream().filter(TurnRecord.Check::passed).count(),
                        checks.size());

        return new EvalSummary(
                run,
                turns,
                count(records, TurnRecord::delivered),
                count(records, TurnRecord::firstEvaluationPass),
                count(records, r -> "RECOVERY".equals(r.outcome())),
                count(records, r -> "TIMED_OUT".equals(r.outcome())),
                Latency.of(records.stream().map(TurnRecord::totalMs).toList()),
                stageLatency,
                turns == 0 ? 0 : (double) calls.size() / turns,
                turns == 0 ? 0 : (double) inputTokens / turns,
                turns == 0 ? 0 : (double) outputTokens / turns,
                costTotal == null || turns == 0 ? null : costTotal / turns,
                costTotal,
                new Rate(formatFailures.size(), providerSuccesses),
                tally(
                        formatFailures.stream()
                                .map(TurnRecord.FormatFailureSummary::operation)
                                .toList()),
                tally(
                        formatFailures.stream()
                                .map(TurnRecord.FormatFailureSummary::reason)
                                .toList()),
                tally(
                        calls.stream()
                                .map(TurnRecord.StageCall::providerFailure)
                                .filter(f -> f != null)
                                .toList()),
                tally(records.stream().flatMap(r -> r.evaluationDecisions().stream()).toList()),
                tally(records.stream().flatMap(r -> r.failureCodes().stream()).toList()),
                expectation,
                byCheck,
                caseRows(records));
    }

    private static List<CaseRow> caseRows(List<TurnRecord> records) {
        Map<String, List<TurnRecord>> byCase =
                records.stream()
                        .collect(
                                Collectors.groupingBy(
                                        TurnRecord::caseId,
                                        LinkedHashMap::new,
                                        Collectors.toList()));
        List<CaseRow> rows = new ArrayList<>();
        byCase.forEach(
                (caseId, list) -> {
                    List<TurnRecord.Check> checks =
                            list.stream().flatMap(r -> r.checks().stream()).toList();
                    List<String> mismatches =
                            checks.stream()
                                    .filter(c -> !c.passed())
                                    .map(
                                            c ->
                                                    c.name()
                                                            + "="
                                                            + (c.actual() == null
                                                                    ? c.status()
                                                                    : c.actual()))
                                    .distinct()
                                    .toList();
                    rows.add(
                            new CaseRow(
                                    caseId,
                                    list.getFirst().suite(),
                                    list.size(),
                                    (int) list.stream().filter(TurnRecord::delivered).count(),
                                    (int)
                                            list.stream()
                                                    .filter(TurnRecord::firstEvaluationPass)
                                                    .count(),
                                    (int) checks.stream().filter(TurnRecord.Check::passed).count(),
                                    checks.size(),
                                    Latency.of(list.stream().map(TurnRecord::totalMs).toList())
                                            .p50(),
                                    mismatches));
                });
        return rows;
    }

    private static Rate count(
            List<TurnRecord> records, java.util.function.Predicate<TurnRecord> predicate) {
        return new Rate((int) records.stream().filter(predicate).count(), records.size());
    }

    /** 많이 나온 순으로 센다. */
    private static Map<String, Integer> tally(List<String> values) {
        Map<String, Integer> counts =
                values.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Function.identity(),
                                        Collectors.collectingAndThen(
                                                Collectors.counting(), Long::intValue)));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Integer>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }
}
