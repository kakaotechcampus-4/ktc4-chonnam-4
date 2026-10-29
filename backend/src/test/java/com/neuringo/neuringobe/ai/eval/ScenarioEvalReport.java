package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시나리오 생성 평가 결과를 파일로 쓴다.
 *
 * <ul>
 *   <li>summary.md: 핵심 지표, 케이스별 결과, 실패 이유별 건수
 *   <li>scenarios.md: 사람이 읽고 검토할 생성 결과(강사 검토 화면처럼)
 *   <li>results.jsonl: 실행 한 번에 한 줄
 *   <li>format-failures.jsonl: 계약 검사에서 실패한 출력 원문과 이유
 * </ul>
 */
final class ScenarioEvalReport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Run(
            String runId,
            String setId,
            String provider,
            String model,
            String startedAt,
            String commit,
            int repeats,
            Double inputPricePerMTok,
            Double outputPricePerMTok,
            String note) {}

    private ScenarioEvalReport() {}

    static String oneLine(List<ScenarioRecord> records) {
        return String.format(
                Locale.ROOT,
                "통과 %s · 첫 시도 통과 %s · 내용 기준 %s · 호출당 p50 %dms p95 %dms",
                ratio(records.stream().filter(ScenarioRecord::delivered).count(), records.size()),
                ratio(
                        records.stream().filter(ScenarioRecord::firstAttemptPass).count(),
                        records.size()),
                ratio(passedChecks(records), totalChecks(records)),
                percentile(callTimes(records), 50),
                percentile(callTimes(records), 95));
    }

    static void write(Path dir, Run run, List<ScenarioRecord> records) {
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("summary.md"), summary(run, records), StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("scenarios.md"), scenarios(run, records), StandardCharsets.UTF_8);
            Files.write(dir.resolve("results.jsonl"), results(records), StandardCharsets.UTF_8);
            Files.write(
                    dir.resolve("format-failures.jsonl"),
                    failures(records),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String summary(Run run, List<ScenarioRecord> records) {
        StringBuilder md = new StringBuilder();
        md.append("# 시나리오 생성 품질 평가 — ").append(run.runId()).append("\n\n");
        md.append("| 항목 | 값 |\n| --- | --- |\n");
        row(md, "평가 세트", run.setId());
        row(md, "제공자 / 모델", run.provider() + " / " + run.model());
        row(md, "시작 시각", run.startedAt());
        row(md, "코드 버전", run.commit());
        row(md, "프롬프트", "scenario-generation/v1");
        row(
                md,
                "반복 × 케이스",
                run.repeats() + "회 × " + (records.size() / Math.max(1, run.repeats())) + "개");
        row(md, "최대 시도", ScenarioEvalRunner.MAX_ATTEMPTS + "회 (계약 검사 실패 시 실패 코드를 붙여 재생성)");
        if (run.note() != null) row(md, "메모", run.note());

        long calls = records.stream().mapToLong(ScenarioRecord::calls).sum();
        long formatFailures = records.stream().mapToLong(r -> r.formatFailures().size()).sum();
        int in = records.stream().mapToInt(ScenarioRecord::inputTokens).sum();
        int out = records.stream().mapToInt(ScenarioRecord::outputTokens).sum();
        md.append("\n## 핵심 지표\n\n| 지표 | 값 |\n| --- | --- |\n");
        row(
                md,
                "최종 통과율 (최대 3회 안에 계약 검사 통과)",
                ratio(records.stream().filter(ScenarioRecord::delivered).count(), records.size()));
        row(
                md,
                "첫 시도 통과율",
                ratio(
                        records.stream().filter(ScenarioRecord::firstAttemptPass).count(),
                        records.size()));
        row(md, "계약 검사 실패율 (실패 / 응답 받은 호출)", ratio(formatFailures, calls));
        row(md, "내용 기준 일치 (통과한 시나리오만)", ratio(passedChecks(records), totalChecks(records)));
        row(
                md,
                "제공자 실패",
                String.valueOf(records.stream().filter(r -> r.providerFailure() != null).count()));
        List<Long> times = callTimes(records);
        row(
                md,
                "호출당 지연 p50 / p95 / 최대",
                percentile(times, 50)
                        + "ms / "
                        + percentile(times, 95)
                        + "ms / "
                        + (times.isEmpty() ? 0 : Collections.max(times))
                        + "ms");
        row(md, "제한 시간 제안", timeoutSuggestion(times));
        row(
                md,
                "케이스당 호출",
                String.format(Locale.ROOT, "%.2f", calls / (double) Math.max(1, records.size())));
        row(
                md,
                "호출당 토큰 (입력 / 출력)",
                (calls == 0 ? 0 : in / calls) + " / " + (calls == 0 ? 0 : out / calls));
        row(md, "시나리오 1개 비용", cost(run, in, out, records.size()));

        md.append(
                "\n## 케이스별\n\n| 케이스 | 난이도 | 통과 | 첫 시도 | 내용 기준 | 실패 이유 |\n| --- | --- | ---: | ---: | ---: | --- |\n");
        Map<String, List<ScenarioRecord>> byCase = new LinkedHashMap<>();
        records.forEach(r -> byCase.computeIfAbsent(r.caseId(), k -> new ArrayList<>()).add(r));
        byCase.forEach(
                (id, list) -> {
                    long passed = list.stream().filter(ScenarioRecord::delivered).count();
                    long first = list.stream().filter(ScenarioRecord::firstAttemptPass).count();
                    long ok = passedChecks(list);
                    long total = totalChecks(list);
                    Map<String, Integer> reasons = reasonCounts(list);
                    md.append("| ")
                            .append(id)
                            .append(" | ")
                            .append(list.getFirst().scenarioLevel())
                            .append(" | ")
                            .append(passed)
                            .append('/')
                            .append(list.size())
                            .append(" | ")
                            .append(first)
                            .append('/')
                            .append(list.size())
                            .append(" | ")
                            .append(total == 0 ? "-" : ok + "/" + total)
                            .append(" | ")
                            .append(reasons.isEmpty() ? "-" : reasons.toString())
                            .append(" |\n");
                });

        md.append("\n## 계약 검사 실패 이유\n\n");
        Map<String, Integer> reasons = reasonCounts(records);
        if (reasons.isEmpty()) md.append("- 없음\n");
        reasons.forEach(
                (reason, count) ->
                        md.append("- `").append(reason).append("`: ").append(count).append('\n'));

        md.append("\n## 어긋난 내용 기준\n\n");
        boolean any = false;
        for (ScenarioRecord record : records) {
            for (ScenarioRegressionSet.Check check : record.checks()) {
                if (!check.passed()) {
                    md.append("- ")
                            .append(record.caseId())
                            .append('#')
                            .append(record.repeat())
                            .append(": ")
                            .append(check.name())
                            .append('\n');
                    any = true;
                }
            }
        }
        if (!any) md.append("- 없음\n");
        md.append(
                "\n> 계약 검사는 형식·개수·길이·금지 표현·위험 낱말·번호 패턴을 본다. 내용 기준은 낱말 포함 여부로 보는 거친 기준이다."
                        + " 교육적 적절성은 scenarios.md 를 사람이 보고 판단한다.\n");
        return md.toString();
    }

    private static String scenarios(Run run, List<ScenarioRecord> records) {
        StringBuilder md = new StringBuilder();
        md.append("# 생성된 시나리오 — ").append(run.runId()).append("\n\n");
        md.append(
                "강사 검토 화면에서 보듯이 읽고, 아래를 확인한다: 학습 목표·조건 반영, 난이도에 맞는 상황, 아동에게 적절한 표현,"
                        + " 대화로 관찰 가능한 목표, 너무 좁거나 넓지 않은 학습 증거, 명확한 사실·금지 추론.\n");
        for (ScenarioRecord record : records) {
            md.append("\n---\n\n## ")
                    .append(record.caseId())
                    .append(" #")
                    .append(record.repeat())
                    .append(" (")
                    .append(record.scenarioLevel())
                    .append(")\n\n");
            GeneratedScenario s = record.scenario();
            if (s == null) {
                md.append("통과한 시나리오 없음. 실패 이유: ")
                        .append(
                                record.providerFailure() != null
                                        ? record.providerFailure()
                                        : reasonCounts(List.of(record)))
                        .append('\n');
                continue;
            }
            md.append("**").append(s.title()).append("** — ").append(s.summary()).append("\n\n");
            md.append("- 등장인물: ");
            s.characters()
                    .forEach(c -> md.append(c.id()).append("(").append(c.role()).append(") "));
            md.append("\n- 말하는 사람: ").append(s.aiRole()).append("\n\n**시나리오 사실**\n\n");
            s.scenarioFacts().forEach(f -> md.append("1. ").append(f).append('\n'));
            md.append("\n**금지 추론**\n\n");
            s.prohibitedInferences().forEach(f -> md.append("- ").append(f).append('\n'));
            md.append("\n**마이크로 목표**\n\n| id | 목표 | 필수 증거 | 달성 예시 |\n| --- | --- | --- | --- |\n");
            for (GeneratedScenario.MicroGoal goal : s.microGoals()) {
                md.append("| ")
                        .append(goal.id())
                        .append(" | ")
                        .append(goal.description())
                        .append(" | ")
                        .append(String.join("<br>", goal.requiredEvidence()))
                        .append(" | ")
                        .append(goal.achievementExample())
                        .append(" |\n");
            }
            md.append("\n**시작 질문**\n\n");
            new TreeMap<>(s.startingQuestions())
                    .forEach(
                            (level, q) ->
                                    md.append("- ")
                                            .append(level)
                                            .append(": ")
                                            .append(q)
                                            .append('\n'));
            List<ScenarioRegressionSet.Check> failed =
                    record.checks().stream().filter(c -> !c.passed()).toList();
            if (!failed.isEmpty()) {
                md.append("\n어긋난 내용 기준: ");
                failed.forEach(c -> md.append(c.name()).append("; "));
                md.append('\n');
            }
        }
        return md.toString();
    }

    private static List<String> results(List<ScenarioRecord> records) {
        return records.stream()
                .map(
                        r -> {
                            Map<String, Object> line = new LinkedHashMap<>();
                            line.put("case_id", r.caseId());
                            line.put("scenario_level", r.scenarioLevel());
                            line.put("repeat", r.repeat());
                            line.put("model", r.model());
                            line.put("delivered", r.delivered());
                            line.put("first_attempt_pass", r.firstAttemptPass());
                            line.put("calls", r.calls());
                            line.put("total_ms", r.totalMs());
                            line.put("call_wall_ms", r.callWallMs());
                            line.put("input_tokens", r.inputTokens());
                            line.put("output_tokens", r.outputTokens());
                            line.put("provider_failure", r.providerFailure());
                            line.put(
                                    "format_failures",
                                    r.formatFailures().stream()
                                            .map(ScenarioRecord.FormatFailure::reason)
                                            .toList());
                            line.put("checks", r.checks());
                            line.put("scenario", r.scenario());
                            return JSON.writeValueAsString(line);
                        })
                .toList();
    }

    private static List<String> failures(List<ScenarioRecord> records) {
        List<String> lines = new ArrayList<>();
        for (ScenarioRecord record : records) {
            for (ScenarioRecord.FormatFailure failure : record.formatFailures()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("case_id", record.caseId());
                line.put("repeat", record.repeat());
                line.put("attempt", failure.attempt());
                line.put("reason", failure.reason());
                line.put("content", failure.content());
                lines.add(JSON.writeValueAsString(line));
            }
        }
        return lines;
    }

    private static Map<String, Integer> reasonCounts(List<ScenarioRecord> records) {
        Map<String, Integer> counts = new TreeMap<>();
        records.forEach(
                r -> r.formatFailures().forEach(f -> counts.merge(f.reason(), 1, Integer::sum)));
        return counts;
    }

    private static long passedChecks(List<ScenarioRecord> records) {
        return records.stream().mapToLong(ScenarioRecord::passedChecks).sum();
    }

    private static long totalChecks(List<ScenarioRecord> records) {
        return records.stream().mapToLong(r -> r.checks().size()).sum();
    }

    private static List<Long> callTimes(List<ScenarioRecord> records) {
        return records.stream().flatMap(r -> r.callWallMs().stream()).toList();
    }

    /** nearest-rank. 보간하지 않고 실제 측정값 하나를 쓴다. */
    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    /** max(p95 × 2, 최대 × 1.5) 를 초 단위로 올린 값. 역할극 측정과 같은 규칙. */
    private static String timeoutSuggestion(List<Long> times) {
        if (times.isEmpty()) return "-";
        double ms = Math.max(percentile(times, 95) * 2.0, Collections.max(times) * 1.5);
        return (long) Math.ceil(ms / 1000.0) + "s";
    }

    private static String cost(Run run, int in, int out, int scenarios) {
        if (run.inputPricePerMTok() == null || run.outputPricePerMTok() == null || scenarios == 0) {
            return "- (단가 미설정)";
        }
        double total = (in * run.inputPricePerMTok() + out * run.outputPricePerMTok()) / 1_000_000;
        return String.format(Locale.ROOT, "$%.5f (전체 $%.4f)", total / scenarios, total);
    }

    private static String ratio(long numerator, long denominator) {
        if (denominator == 0) return "-";
        return String.format(
                Locale.ROOT,
                "%.1f%% (%d/%d)",
                100.0 * numerator / denominator,
                numerator,
                denominator);
    }

    private static void row(StringBuilder md, String key, String value) {
        md.append("| ")
                .append(key)
                .append(" | ")
                .append(value == null ? "-" : value)
                .append(" |\n");
    }
}
