package com.neuringo.neuringobe.ai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 결과를 파일로 쓴다.
 *
 * <ul>
 *   <li>turns.jsonl: 턴 한 번에 한 줄. 단계별 지연·토큰·판정·기대값 비교. 모델 출력 원문은 전달된 응답 문장만 들어간다.
 *   <li>format-failures.jsonl: 형식 실패한 호출의 원문과 거부 이유
 *   <li>summary.json / summary.md: 집계
 * </ul>
 */
final class EvalReport {

    static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    private EvalReport() {}

    static void writeRun(
            Path dir,
            EvalSummary summary,
            List<TurnRecord> records,
            List<RoleplayEvalRunner.FormatSample> formatSamples) {
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("turns.jsonl"), jsonLines(records), StandardCharsets.UTF_8);
            Files.write(
                    dir.resolve("format-failures.jsonl"),
                    jsonLines(formatSamples),
                    StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("summary.json"),
                    JSON.writer(SerializationFeature.INDENT_OUTPUT).writeValueAsString(summary),
                    StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("summary.md"), markdown(summary), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static EvalSummary readSummary(Path summaryJson) {
        try {
            return JSON.readValue(Files.readString(summaryJson), EvalSummary.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> jsonLines(List<?> values) {
        return values.stream().map(JSON::writeValueAsString).toList();
    }

    static String markdown(EvalSummary s) {
        EvalSummary.Run run = s.run();
        StringBuilder md = new StringBuilder();
        md.append("# 역할극 품질 회귀 측정 — ").append(run.runId()).append("\n\n");
        md.append("| 항목 | 값 |\n| --- | --- |\n");
        row(md, "회귀 세트", run.setId());
        row(md, "제공자 / 모델", run.provider() + " / " + nullable(run.model()));
        row(md, "시작 시각", run.startedAt());
        row(md, "코드 버전", nullable(run.gitCommit()));
        row(md, "프롬프트", run.promptVersions());
        row(
                md,
                "반복 × 턴",
                run.repeats()
                        + "회 × "
                        + (s.turns() / Math.max(1, run.repeats()))
                        + "턴 = "
                        + s.turns()
                        + "턴");
        row(md, "턴 예산", run.turnBudgetMs() + "ms");
        row(
                md,
                "단가(USD / 1M 토큰)",
                run.priceInputPerMTok() == null
                        ? "미설정 — 비용은 계산하지 않음"
                        : "입력 " + run.priceInputPerMTok() + " · 출력 " + run.priceOutputPerMTok());
        if (run.note() != null && !run.note().isBlank()) row(md, "메모", run.note());

        md.append("\n## 핵심 지표\n\n| 지표 | 값 |\n| --- | --- |\n");
        row(md, "전달 성공률", s.delivered().percent());
        row(md, "첫 판단 PASS율 (재생성 없이 통과)", s.firstEvaluationPass().percent());
        row(md, "복구 경로", s.recovery().percent());
        row(md, "턴 예산 초과", s.timedOut().percent());
        row(md, "기대값 일치율", s.expectation().percent());
        row(md, "형식 실패율 (형식 실패 / 응답 받은 호출)", s.formatFailure().percent());
        row(md, "턴 지연 p50 / p95 / 최대", ms(s.turnLatency()));
        row(md, "턴당 LLM 호출", "%.2f".formatted(s.llmCallsPerTurn()));
        row(
                md,
                "턴당 토큰 (입력 / 출력)",
                "%.0f / %.0f".formatted(s.inputTokensPerTurn(), s.outputTokensPerTurn()));
        row(
                md,
                "턴당 비용 / 전체",
                s.costPerTurnUsd() == null
                        ? "-"
                        : "$%.5f / $%.4f".formatted(s.costPerTurnUsd(), s.costTotalUsd()));

        md.append(
                "\n## 단계별 지연 (호출 한 번 기준)\n\n| 단계 | p50 | p95 | 최대 | 호출 수 | 제한 시간 제안 |\n| --- | ---: | ---: | ---: | ---: | ---: |\n");
        s.stageLatency()
                .forEach(
                        (stage, l) ->
                                md.append(
                                        "| %s | %d | %d | %d | %d | %s |\n"
                                                .formatted(
                                                        stage,
                                                        l.p50(),
                                                        l.p95(),
                                                        l.max(),
                                                        l.samples(),
                                                        l.suggestedTimeout())));

        md.append(
                "\n## 케이스별\n\n| 케이스 | 스위트 | 전달 | 첫 PASS | 기대값 | p50(ms) | 어긋난 항목 |\n| --- | --- | ---: | ---: | ---: | ---: | --- |\n");
        for (EvalSummary.CaseRow c : s.cases()) {
            md.append(
                    "| %s | %s | %d/%d | %d/%d | %d/%d | %d | %s |\n"
                            .formatted(
                                    c.caseId(),
                                    c.suite(),
                                    c.delivered(),
                                    c.runs(),
                                    c.firstEvaluationPass(),
                                    c.runs(),
                                    c.checksPassed(),
                                    c.checksTotal(),
                                    c.p50Ms(),
                                    c.mismatches().isEmpty()
                                            ? "-"
                                            : String.join(", ", c.mismatches())));
        }

        md.append("\n## 기대값 항목별 일치율\n\n");
        bulletRates(md, s.expectationByCheck());
        md.append("\n## 응답 판단 결과\n\n");
        bullets(md, s.evaluationDecisions());
        md.append("\n## 응답 판단 실패 코드\n\n");
        bullets(md, s.failureCodes());
        md.append("\n## 형식 실패\n\n단계별\n\n");
        bullets(md, s.formatFailuresByOperation());
        md.append("\n이유별 (원문은 format-failures.jsonl)\n\n");
        bullets(md, s.formatFailureReasons());
        md.append("\n## 제공자 실패\n\n");
        bullets(md, s.providerFailures());
        md.append(
                "\n> 제한 시간 제안 = max(p95 × 2, 최대 × 1.5) 를 초 단위로 올림. LLM 은 단계마다 AI_TIMEOUT 하나를 쓰므로 세 단계 중 큰 값을 본다.\n");
        md.append(
                "\n> 지연은 이 실행 환경에서 제공자까지 왕복한 시간이다. 한 번 실행은 탐색 결과이고, 모델 비교는 같은 조건으로 3회 이상 반복한 값을 쓴다.\n");
        return md.toString();
    }

    /** 여러 실행의 summary.json 을 모아 모델 비교표를 만든다. */
    static String comparison(List<EvalSummary> summaries) {
        StringBuilder md = new StringBuilder("# 역할극 품질 회귀 — 모델 비교\n\n");
        List<String> header = new ArrayList<>(List.of("지표"));
        summaries.forEach(
                s -> header.add(nullable(s.run().model()) + "<br>`" + s.run().runId() + "`"));
        md.append("| ").append(String.join(" | ", header)).append(" |\n");
        md.append("| --- ").append(" | ---:".repeat(summaries.size())).append(" |\n");
        compareRow(
                md,
                "반복 × 턴",
                summaries,
                s -> s.run().repeats() + " × " + s.turns() / Math.max(1, s.run().repeats()));
        compareRow(md, "전달 성공률", summaries, s -> s.delivered().percent());
        compareRow(md, "첫 판단 PASS율", summaries, s -> s.firstEvaluationPass().percent());
        compareRow(md, "기대값 일치율", summaries, s -> s.expectation().percent());
        compareRow(md, "형식 실패율", summaries, s -> s.formatFailure().percent());
        compareRow(
                md,
                "복구 / 예산 초과",
                summaries,
                s -> s.recovery().count() + " / " + s.timedOut().count());
        compareRow(md, "턴 지연 p50", summaries, s -> s.turnLatency().p50() + "ms");
        compareRow(md, "턴 지연 p95", summaries, s -> s.turnLatency().p95() + "ms");
        compareRow(md, "턴 지연 최대", summaries, s -> s.turnLatency().max() + "ms");
        for (String stage :
                List.of("CAUSE_ANALYSIS", "RESPONSE_GENERATION", "RESPONSE_EVALUATION")) {
            compareRow(
                    md,
                    stage + " p50 / p95",
                    summaries,
                    s -> {
                        EvalSummary.Latency l = s.stageLatency().get(stage);
                        return l == null ? "-" : l.p50() + " / " + l.p95() + "ms";
                    });
        }
        compareRow(md, "턴당 LLM 호출", summaries, s -> "%.2f".formatted(s.llmCallsPerTurn()));
        compareRow(
                md,
                "턴당 토큰 (입력/출력)",
                summaries,
                s -> "%.0f / %.0f".formatted(s.inputTokensPerTurn(), s.outputTokensPerTurn()));
        compareRow(
                md,
                "턴당 비용",
                summaries,
                s -> s.costPerTurnUsd() == null ? "-" : "$%.5f".formatted(s.costPerTurnUsd()));
        md.append("\n### 케이스별 기대값 일치 (통과/검사)\n\n");
        List<String> caseIds =
                summaries.stream()
                        .flatMap(s -> s.cases().stream().map(EvalSummary.CaseRow::caseId))
                        .distinct()
                        .toList();
        md.append("| 케이스")
                .append(" | ")
                .append(
                        summaries.stream()
                                .map(s -> "`" + s.run().runId() + "`")
                                .collect(Collectors.joining(" | ")))
                .append(" |\n");
        md.append("| ---").append(" | ---:".repeat(summaries.size())).append(" |\n");
        for (String caseId : caseIds) {
            md.append("| ").append(caseId);
            for (EvalSummary s : summaries) {
                md.append(" | ")
                        .append(
                                s.cases().stream()
                                        .filter(c -> c.caseId().equals(caseId))
                                        .findFirst()
                                        .map(c -> c.checksPassed() + "/" + c.checksTotal())
                                        .orElse("-"));
            }
            md.append(" |\n");
        }
        return md.toString();
    }

    private static void compareRow(
            StringBuilder md,
            String name,
            List<EvalSummary> summaries,
            java.util.function.Function<EvalSummary, String> value) {
        md.append("| ").append(name);
        summaries.forEach(s -> md.append(" | ").append(value.apply(s)));
        md.append(" |\n");
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    private static void bullets(StringBuilder md, Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            md.append("- 없음\n");
            return;
        }
        counts.forEach(
                (key, count) ->
                        md.append("- `").append(key).append("`: ").append(count).append("\n"));
    }

    private static void bulletRates(StringBuilder md, Map<String, EvalSummary.Rate> rates) {
        if (rates.isEmpty()) {
            md.append("- 없음\n");
            return;
        }
        rates.forEach(
                (key, rate) ->
                        md.append("- `")
                                .append(key)
                                .append("`: ")
                                .append(rate.percent())
                                .append("\n"));
    }

    private static String ms(EvalSummary.Latency l) {
        return "%dms / %dms / %dms".formatted(l.p50(), l.p95(), l.max());
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }
}
