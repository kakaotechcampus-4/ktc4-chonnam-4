package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.report.ReportRound;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import com.neuringo.neuringobe.report.application.RecommendationComposer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** reportEval 결과를 마크다운 한 장으로 쓴다. 회귀 세트가 합성 문장이라 초안 문장도 그대로 싣는다. 키·엔드포인트 주소는 싣지 않는다. */
final class ReportEvalReport {

    /** 호출 목적별 1M 토큰 단가(원). 응답의 모델 이름은 날짜가 붙어 설정값과 다를 수 있어서 목적으로 찾는다. 없으면 비용을 비운다. */
    record Price(double inputPerMillion, double outputPerMillion) {}

    record Meta(
            String runId,
            String startedAt,
            String gitCommit,
            String setId,
            String generationModel,
            String reviewModel,
            int repeats) {}

    private ReportEvalReport() {}

    static String render(
            Meta meta,
            List<ReportRecord> records,
            Map<AiOperation, Price> prices,
            List<SimilarityProbeResult> probes,
            String embeddingModel,
            Integer embeddingTokens,
            Double embeddingPricePerMillion) {
        StringBuilder out = new StringBuilder();
        out.append("# 학습 리포트 루프 실측 — ").append(meta.runId()).append("\n\n");
        out.append("| 항목 | 값 |\n|---|---|\n");
        row(out, "시작", meta.startedAt());
        row(out, "코드", meta.gitCommit() == null ? "-" : "`" + meta.gitCommit() + "`");
        row(out, "회귀 세트", meta.setId());
        row(out, "초안 모델", "`" + meta.generationModel() + "`");
        row(out, "평가 모델", "`" + meta.reviewModel() + "`");
        row(out, "반복", String.valueOf(meta.repeats()));
        out.append('\n');

        summary(out, records, prices);
        cases(out, records, prices);
        codes(out, records);
        formatFailureSamples(out, records);
        drafts(out, records);
        if (!probes.isEmpty())
            similarity(out, probes, embeddingModel, embeddingTokens, embeddingPricePerMillion);
        return out.toString();
    }

    private static void summary(
            StringBuilder out, List<ReportRecord> records, Map<AiOperation, Price> prices) {
        int total = records.size();
        long passed = records.stream().filter(ReportRecord::displayable).count();
        long firstRound = records.stream().filter(ReportRecord::passedFirstRound).count();
        double meanRounds = records.stream().mapToInt(r -> r.rounds().size()).average().orElse(0);
        long ruleRejected = rounds(records, ReportRound.Outcome.RULES_REJECTED);
        long reviewRejected = rounds(records, ReportRound.Outcome.REVIEW_REJECTED);
        int formatFailures = records.stream().mapToInt(ReportRecord::formatFailures).sum();
        long checkFailed = records.stream().filter(r -> !r.checkFailures().isEmpty()).count();
        double meanScore =
                records.stream()
                        .filter(r -> r.finalScores() != null)
                        .mapToDouble(r -> r.finalScores().normalized())
                        .average()
                        .orElse(Double.NaN);
        List<RecordingLlmProvider.Call> calls =
                records.stream().flatMap(r -> r.calls().stream()).toList();
        Double cost = cost(calls, prices);

        out.append("## 요약\n\n| 지표 | 값 |\n|---|---|\n");
        row(out, "통과(강사에게 보임)", "%d / %d (%s)".formatted(passed, total, percent(passed, total)));
        row(
                out,
                "첫 바퀴 통과",
                "%d / %d (%s)".formatted(firstRound, total, percent(firstRound, total)));
        row(out, "평균 바퀴 수", "%.2f".formatted(meanRounds));
        row(out, "규칙 검사 반려", ruleRejected + "번");
        row(out, "평가 모델 반려", reviewRejected + "번");
        row(out, "출력 형식 실패 호출", formatFailures + "번");
        row(out, "통과했지만 기대값 위반", checkFailed + "건");
        row(out, "통과 리포트 평가 점수(0~1)", Double.isNaN(meanScore) ? "-" : "%.3f".formatted(meanScore));
        row(out, "호출 수", String.valueOf(calls.size()));
        row(
                out,
                "토큰(입력 / 출력)",
                "%d / %d"
                        .formatted(
                                records.stream().mapToInt(ReportRecord::inputTokens).sum(),
                                records.stream().mapToInt(ReportRecord::outputTokens).sum()));
        row(
                out,
                "비용",
                cost == null
                        ? "-"
                        : "₩%.1f (리포트당 ₩%.1f)".formatted(cost, cost / Math.max(1, total)));
        for (AiOperation operation :
                List.of(AiOperation.REPORT_GENERATION, AiOperation.REPORT_REVIEW)) {
            List<Long> walls =
                    calls.stream()
                            .filter(c -> c.operation() == operation)
                            .map(RecordingLlmProvider.Call::wallMs)
                            .sorted()
                            .toList();
            if (walls.isEmpty()) continue;
            row(
                    out,
                    operation.name() + " 지연(p50 / p95 / 최대)",
                    "%dms / %dms / %dms"
                            .formatted(
                                    percentile(walls, 0.5),
                                    percentile(walls, 0.95),
                                    walls.getLast()));
            // 종료 이유가 length 면 출력 상한에 걸려 응답이 잘린 것이다(추론 토큰 포함).
            String finishes =
                    calls.stream()
                            .filter(c -> c.operation() == operation)
                            .collect(
                                    Collectors.groupingBy(
                                            c -> String.valueOf(c.finishReason()),
                                            TreeMap::new,
                                            Collectors.counting()))
                            .entrySet()
                            .stream()
                            .map(e -> e.getKey() + " " + e.getValue())
                            .collect(Collectors.joining(" · "));
            row(out, operation.name() + " 종료 이유", finishes);
        }
        out.append('\n');
    }

    private static void cases(
            StringBuilder out, List<ReportRecord> records, Map<AiOperation, Price> prices) {
        out.append("## 사례별 결과\n\n");
        out.append("| 사례 | 회차 | 목적 | 결과 | 바퀴 | 최종 점수 | 호출 | 형식 실패 | 기대값 위반 | 시간 | 비용 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ReportRecord record : records) {
            Double cost = cost(record.calls(), prices);
            out.append("| ")
                    .append(record.caseId())
                    .append(" | ")
                    .append(record.repeat())
                    .append(" | ")
                    .append(record.purpose())
                    .append(" | ")
                    .append(record.displayable() ? "통과" : "실패 `" + record.failureReason() + "`")
                    .append(" | ")
                    .append(
                            record.rounds().stream()
                                    .map(ReportEvalReport::roundLabel)
                                    .collect(Collectors.joining(" → ")))
                    .append(" | ")
                    .append(
                            record.finalScores() == null
                                    ? "-"
                                    : "%.2f".formatted(record.finalScores().normalized()))
                    .append(" | ")
                    .append(record.calls().size())
                    .append(" | ")
                    .append(record.formatFailures())
                    .append(" | ")
                    .append(
                            record.checkFailures().isEmpty()
                                    ? "-"
                                    : "`" + String.join("`, `", record.checkFailures()) + "`")
                    .append(" | ")
                    .append("%.1fs".formatted(record.wallMs() / 1000.0))
                    .append(" | ")
                    .append(cost == null ? "-" : "₩%.2f".formatted(cost))
                    .append(" |\n");
        }
        out.append('\n');
    }

    private static void codes(StringBuilder out, List<ReportRecord> records) {
        Map<String, Long> counts =
                records.stream()
                        .flatMap(r -> r.rounds().stream())
                        .filter(round -> round.outcome() != ReportRound.Outcome.PASSED)
                        .flatMap(round -> round.failureCodes().stream())
                        .collect(
                                Collectors.groupingBy(
                                        Function.identity(), TreeMap::new, Collectors.counting()));
        out.append("## 돌려보낸 이유\n\n");
        if (counts.isEmpty()) {
            out.append("돌려보낸 바퀴가 없다.\n\n");
            return;
        }
        out.append("| 코드 | 횟수 |\n|---|---|\n");
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(
                        entry ->
                                row(
                                        out,
                                        "`" + entry.getKey() + "`",
                                        String.valueOf(entry.getValue())));
        out.append('\n');
    }

    private static void formatFailureSamples(StringBuilder out, List<ReportRecord> records) {
        List<String> lines =
                records.stream()
                        .flatMap(
                                r ->
                                        r.formatFailureSamples().stream()
                                                .map(s -> r.caseId() + "#" + r.repeat() + " " + s))
                        .toList();
        if (lines.isEmpty()) return;
        out.append("## 출력 형식 실패 원문\n\n");
        lines.forEach(line -> out.append("- `").append(line.replace("`", "'")).append("`\n"));
        out.append('\n');
    }

    private static void drafts(StringBuilder out, List<ReportRecord> records) {
        out.append("## 리포트 문장 (회차 1)\n\n");
        for (ReportRecord record : records) {
            if (record.repeat() != 1) continue;
            out.append("### ")
                    .append(record.caseId())
                    .append(" — ")
                    .append(record.purpose())
                    .append("\n\n");
            ReportDraft draft = record.draft();
            if (!record.displayable()) {
                out.append("통과하지 못해 강사에게 보이지 않는다(`").append(record.failureReason()).append("`).");
                if (draft == null) {
                    out.append("\n\n");
                    continue;
                }
                List<String> lastCodes =
                        record.rounds().isEmpty()
                                ? List.of()
                                : record.rounds().getLast().failureCodes();
                out.append(" 아래는 마지막 초안이고, 마지막 바퀴에서 돌려보낸 코드는 `")
                        .append(String.join("`, `", lastCodes))
                        .append("` 이다.\n\n");
            }
            out.append("- 요약: ").append(draft.summary()).append('\n');
            section(out, "관찰", draft.observations());
            section(out, "효과적인 지원", draft.effectiveSupports());
            section(out, "어려운 지점", draft.difficultPoints());
            out.append('\n');
        }
    }

    private static void section(StringBuilder out, String name, List<ReportItem> items) {
        if (items.isEmpty()) {
            out.append("- ").append(name).append(": (없음)\n");
            return;
        }
        for (ReportItem item : items) {
            out.append("- ")
                    .append(name)
                    .append(": ")
                    .append(item.text())
                    .append(" `")
                    .append(String.join(",", item.evidenceIds()))
                    .append("`\n");
        }
    }

    private static void similarity(
            StringBuilder out,
            List<SimilarityProbeResult> probes,
            String model,
            Integer tokens,
            Double pricePerMillion) {
        out.append("## 목표 유사도 실측\n\n");
        out.append("임베딩 모델 `")
                .append(model)
                .append("`, 기준값은 관련 ≥ ")
                .append(RecommendationComposer.DEFAULT_MIN_SIMILARITY)
                .append(", 같은 목표 ≥ ")
                .append(RecommendationComposer.DEFAULT_DUPLICATE_SIMILARITY)
                .append("다.");
        if (tokens != null) {
            out.append(" 임베딩 입력 ").append(tokens).append("토큰");
            if (pricePerMillion != null)
                out.append(", ₩%.3f".formatted(tokens * pricePerMillion / 1_000_000));
            out.append('.');
        }
        out.append("\n\n");
        for (SimilarityProbeResult probe : probes) {
            out.append("질의: **").append(probe.query()).append("**\n\n");
            out.append("| 목표 | 유사도 | 세트가 정한 관계 | 기준값으로 나눈 결과 | 맞음 |\n|---|---|---|---|---|\n");
            List<SimilarityProbeResult.Row> rows = new ArrayList<>(probe.rows());
            rows.sort(Comparator.comparingDouble(SimilarityProbeResult.Row::similarity).reversed());
            for (SimilarityProbeResult.Row r : rows) {
                out.append("| ")
                        .append(r.title())
                        .append(" | ")
                        .append("%.3f".formatted(r.similarity()))
                        .append(" | ")
                        .append(r.expected())
                        .append(" | ")
                        .append(r.band())
                        .append(" | ")
                        .append(r.matches() ? "✅" : "❌")
                        .append(" |\n");
            }
            out.append("\n확장 추천이 고른 다음 목표: ")
                    .append(probe.suggestion() == null ? "(없음)" : probe.suggestion())
                    .append("\n\n");
        }
    }

    private static String roundLabel(ReportRound round) {
        return switch (round.outcome()) {
            case PASSED -> "통과";
            case RULES_REJECTED -> "규칙 반려";
            case REVIEW_REJECTED -> "평가 반려";
            case CALL_FAILED -> "호출 실패";
        };
    }

    private static long rounds(List<ReportRecord> records, ReportRound.Outcome outcome) {
        return records.stream()
                .flatMap(r -> r.rounds().stream())
                .filter(round -> round.outcome() == outcome)
                .count();
    }

    static Double cost(List<RecordingLlmProvider.Call> calls, Map<AiOperation, Price> prices) {
        double total = 0;
        for (RecordingLlmProvider.Call call : calls) {
            Price price = prices.get(call.operation());
            if (price == null) return null;
            int in = call.inputTokens() == null ? 0 : call.inputTokens();
            int outTokens = call.outputTokens() == null ? 0 : call.outputTokens();
            total +=
                    in * price.inputPerMillion() / 1_000_000
                            + outTokens * price.outputPerMillion() / 1_000_000;
        }
        return total;
    }

    private static long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String percent(long part, long total) {
        return total == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / total);
    }

    private static void row(StringBuilder out, String name, String value) {
        out.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }
}
