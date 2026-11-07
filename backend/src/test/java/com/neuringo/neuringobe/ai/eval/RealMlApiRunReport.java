package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.eval.RealMlApiTestConfiguration.CallLog.LlmCall;
import com.neuringo.neuringobe.ai.eval.RealMlApiTestConfiguration.CallLog.SttCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 ML API 실측 결과를 build/mlapi-it/&lt;run&gt;/ 에 쓴다: summary.md(사람용),
 * turns.jsonl·calls.jsonl·stt.jsonl(원자료).
 *
 * <p>팀 코드의 {@code StructuredLlmExecutor} 는 형식·계약 검사에 걸린 이유를 버리고 {@code INVALID_OUTPUT_FORMAT} 만
 * 남긴다. 그래서 여기서는 "제공자 호출은 성공했는데 같은 단계가 바로 다음 시도로 다시 불림"을 검사 거절로 보고, 출력 원문으로 이유를 추정한다(추정임을 보고서에 적는다).
 */
final class RealMlApiRunReport {

    static final Set<String> STATUSES =
            Set.of(
                    "DELIVERED",
                    "REINPUT_REQUIRED",
                    "RETRY_REQUIRED",
                    "STOPPED",
                    "NOTICE_UNAVAILABLE",
                    "PROCESSING");
    static final int MAXIMUM_TURN_COUNT = 10;
    static final long TURN_BUDGET_MS = 60_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RealMlApiRunReport() {}

    static final class Run {
        final String persona;
        final int repeat;
        UUID activityId;
        UUID sessionId;
        String scenarioLevel;
        List<Map<String, Object>> openingMessages = List.of();
        final List<Turn> turns = new ArrayList<>();
        String endReason;
        String finalActivityStatus;
        String sessionStatus;
        Integer lastTurnNumber;
        Integer committedTurns;
        String setupError;

        Run(String persona, int repeat) {
            this.persona = persona;
            this.repeat = repeat;
        }

        String label() {
            return persona + "#" + repeat;
        }
    }

    static final class Turn {
        final int index;
        final String kind;
        final String input;
        int httpStatus;
        long latencyMs;
        String status;
        String action;
        String errorCode;
        String acceptedInput;
        List<Map<String, Object>> messages = List.of();
        List<LlmCall> llmCalls = List.of();
        List<SttCall> sttCalls = List.of();

        Turn(int index, String kind, String input) {
            this.index = index;
            this.kind = kind;
            this.input = input;
        }

        boolean delivered() {
            return "DELIVERED".equals(status);
        }
    }

    /** 호출 하나에 대한 판정: 받아들여짐·제공자 실패·검사 거절(추정)·평가 결정. */
    record Verdict(String kind, String detail) {
        String label() {
            return detail == null ? kind : kind + "(" + detail + ")";
        }
    }

    static double cost(List<LlmCall> calls, RealMlApiSettings s) {
        double total = 0;
        for (LlmCall c : calls) {
            if (c.inputTokens() != null) total += c.inputTokens() / 1e6 * s.priceInputPerMillion();
            if (c.outputTokens() != null)
                total += c.outputTokens() / 1e6 * s.priceOutputPerMillion();
        }
        return total;
    }

    /**
     * @param rejections 계측된 파이프라인이 남긴 실제 거절 이유(호출 번호 → 파서 예외 메시지). 있으면 추정 대신 이것을 쓴다.
     */
    static List<Verdict> verdicts(Turn turn, Map<Integer, String> rejections) {
        List<LlmCall> calls = turn.llmCalls;
        List<Verdict> out = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            LlmCall c = calls.get(i);
            LlmCall next = i + 1 < calls.size() ? calls.get(i + 1) : null;
            if (rejections.containsKey(c.seq())) {
                out.add(new Verdict("REJECTED", rejections.get(c.seq()) + " | " + diagnose(c)));
            } else if (c.failureType() != null) {
                out.add(
                        new Verdict(
                                "PROVIDER_FAILURE",
                                c.failureType()
                                        + (c.providerErrorCode() == null
                                                ? ""
                                                : " " + c.providerErrorCode())));
            } else if (next != null
                    && next.operation() == c.operation()
                    && next.attempt() == c.attempt() + 1) {
                out.add(new Verdict("REJECTED", diagnose(c)));
            } else if (c.operation() == AiOperation.RESPONSE_EVALUATION) {
                String decision = field(c.content(), "decision");
                out.add(
                        decision == null
                                ? new Verdict("REJECTED?", diagnose(c))
                                : new Verdict("EVALUATION", decision));
            } else if (next == null && !turn.delivered()) {
                out.add(new Verdict("REJECTED?", diagnose(c)));
            } else {
                out.add(new Verdict("ACCEPTED", null));
            }
        }
        return out;
    }

    /** 검사 거절로 보이는 출력의 이유 추정. 팀 코드가 실제 이유를 남기지 않아서 출력 원문으로만 본다. */
    static String diagnose(LlmCall c) {
        if (c.finishReason() != null && c.finishReason().equalsIgnoreCase("length")) {
            return "TRUNCATED finish=length out=" + c.outputTokens();
        }
        if (c.content() == null || c.content().isBlank()) return "EMPTY";
        JsonNode node = tree(c.content());
        if (node == null) return "NOT_JSON";
        if (c.operation() == AiOperation.RESPONSE_GENERATION) {
            String text = node.path("text").asString(null);
            String type = node.path("response_type").asString(null);
            int length = text == null ? -1 : text.codePointCount(0, text.length());
            return "type="
                    + type
                    + " len="
                    + length
                    + (length > 80 ? " TOO_LONG" : "")
                    + (text != null && (text.contains("?") || text.contains("？")) ? " has?" : "");
        }
        if (c.operation() == AiOperation.RESPONSE_EVALUATION) {
            JsonNode revision = node.path("revision_instruction");
            String revisionKind =
                    revision.isMissingNode()
                            ? "missing"
                            : revision.isNull()
                                    ? "null"
                                    : revision.isObject()
                                            ? "object"
                                            : revision.isString() ? "string" : "other";
            return "decision="
                    + node.path("decision").asString(null)
                    + " retry_target="
                    + node.path("retry_target").asString(null)
                    + " revision_instruction="
                    + revisionKind;
        }
        return "JSON_OK(contract check)";
    }

    static void write(
            Path out,
            RealMlApiSettings s,
            List<Run> runs,
            List<LlmCall> calls,
            List<SttCall> stt,
            String stopReason,
            Map<Integer, String> rejections) {
        try {
            Files.createDirectories(out);
            writeJsonl(out.resolve("calls.jsonl"), calls.stream().map(c -> (Object) c).toList());
            writeJsonl(out.resolve("stt.jsonl"), stt.stream().map(c -> (Object) c).toList());
            List<Object> turnRows = new ArrayList<>();
            for (Run run : runs) {
                for (Turn t : run.turns) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("run", run.label());
                    row.put("turn", t.index);
                    row.put("kind", t.kind);
                    row.put("input", t.input);
                    row.put("http", t.httpStatus);
                    row.put("status", t.status);
                    row.put("action", t.action);
                    row.put("errorCode", t.errorCode);
                    row.put("latencyMs", t.latencyMs);
                    row.put("acceptedInput", t.acceptedInput);
                    row.put("messages", t.messages);
                    row.put("calls", t.llmCalls.stream().map(LlmCall::seq).toList());
                    row.put(
                            "verdicts",
                            verdicts(t, rejections).stream().map(Verdict::label).toList());
                    row.put("violations", violations(t));
                    turnRows.add(row);
                }
            }
            writeJsonl(out.resolve("turns.jsonl"), turnRows);
            Files.writeString(
                    out.resolve("summary.md"),
                    summary(s, runs, calls, stt, stopReason, rejections),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<String> violations(Turn t) {
        List<String> v = new ArrayList<>();
        if (t.httpStatus >= 500) v.add("HTTP " + t.httpStatus + " " + t.errorCode);
        if (t.httpStatus == 200 && (t.status == null || !STATUSES.contains(t.status)))
            v.add("unknown status " + t.status);
        if (t.latencyMs > TURN_BUDGET_MS + 2_000) v.add("over turn budget " + t.latencyMs + "ms");
        if (t.delivered() && t.messages.isEmpty()) v.add("DELIVERED without messages");
        for (Map<String, Object> m : t.messages) {
            Object text = m.get("text");
            if (text == null || String.valueOf(text).isBlank()) v.add("message without text");
        }
        return v;
    }

    private static String summary(
            RealMlApiSettings s,
            List<Run> runs,
            List<LlmCall> calls,
            List<SttCall> stt,
            String stopReason,
            Map<Integer, String> rejections) {
        StringBuilder md = new StringBuilder();
        List<Turn> turns = runs.stream().flatMap(r -> r.turns.stream()).toList();
        md.append("# 실제 ML API 역할극 실측 — ").append(s.runId()).append("\n\n");
        md.append("| 항목 | 값 |\n|---|---|\n");
        row(md, "만든 시각", LocalDateTime.now().withNano(0).toString());
        row(md, "모델", s.alias() + " (`" + s.modelId() + "`)");
        row(md, "주소 규칙", "no-v1".equals(s.baseMode()) ? "끝 /v1 뗌(기본값 규칙)" : "모델 주소 그대로(/v1)");
        row(md, "호출 제한(AI_TIMEOUT)", s.timeout());
        row(md, "턴 예산·최대 턴", "60s · " + MAXIMUM_TURN_COUNT + "턴, 목표별 지원 3턴");
        row(
                md,
                "회차",
                runs.size() + "개 (" + String.join(", ", s.personas()) + " × " + s.repeats() + ")");
        row(md, "보낸 입력", String.valueOf(turns.size()));
        row(md, "LLM 호출", String.valueOf(calls.size()));
        row(md, "STT 호출", String.valueOf(stt.size()));
        row(md, "비용(토큰 기준)", String.format(Locale.ROOT, "₩%.1f", cost(calls, s)));
        if (stopReason != null) row(md, "멈춘 이유", stopReason);
        md.append(
                "\n> 입력 처리기·음성 전달·TTS 는 테스트 대역이다(운영 구현 없음). 그래서 개인정보 가림·위험 입력 차단·음성 출력은 이 실측의 범위 밖이다.\n\n");

        md.append("## 1. 회차별 결과\n\n");
        md.append(
                "| 회차 | 시나리오 | 입력 | DELIVERED | REINPUT | RETRY | NOTICE_UNAVAILABLE | STOPPED | 그 밖 | 끝난 이유 | 활동 | 확정 턴(last) | 준비 오류 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Run r : runs) {
            Map<String, Integer> counts = new TreeMap<>();
            for (Turn t : r.turns) counts.merge(statusKey(t), 1, Integer::sum);
            int known =
                    counts.getOrDefault("DELIVERED", 0)
                            + counts.getOrDefault("REINPUT_REQUIRED", 0)
                            + counts.getOrDefault("RETRY_REQUIRED", 0)
                            + counts.getOrDefault("NOTICE_UNAVAILABLE", 0)
                            + counts.getOrDefault("STOPPED", 0);
            md.append("| ")
                    .append(r.label())
                    .append(" | ")
                    .append(nz(r.scenarioLevel))
                    .append(" | ")
                    .append(r.turns.size())
                    .append(" | ")
                    .append(counts.getOrDefault("DELIVERED", 0))
                    .append(" | ")
                    .append(counts.getOrDefault("REINPUT_REQUIRED", 0))
                    .append(" | ")
                    .append(counts.getOrDefault("RETRY_REQUIRED", 0))
                    .append(" | ")
                    .append(counts.getOrDefault("NOTICE_UNAVAILABLE", 0))
                    .append(" | ")
                    .append(counts.getOrDefault("STOPPED", 0))
                    .append(" | ")
                    .append(r.turns.size() - known)
                    .append(" | ")
                    .append(nz(r.endReason))
                    .append(" | ")
                    .append(nz(r.finalActivityStatus))
                    .append(" | ")
                    .append(r.committedTurns == null ? "-" : r.committedTurns)
                    .append("(")
                    .append(r.lastTurnNumber == null ? "-" : r.lastTurnNumber)
                    .append(") | ")
                    .append(r.setupError == null ? "" : cell(r.setupError, 160))
                    .append(" |\n");
        }

        md.append("\n## 2. 턴 응답\n\n");
        Map<String, Integer> statusCounts = new TreeMap<>();
        for (Turn t : turns)
            statusCounts.merge(
                    t.httpStatus + " " + statusKey(t) + "/" + nz(t.action), 1, Integer::sum);
        md.append("| HTTP · status/action | 수 |\n|---|---|\n");
        statusCounts.forEach(
                (k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n| 입력 종류 | 수 | 지연 p50 | p95 | 최대 |\n|---|---|---|---|---|\n");
        for (String kind : List.of("text", "voice")) {
            List<Long> l =
                    turns.stream().filter(t -> t.kind.equals(kind)).map(t -> t.latencyMs).toList();
            if (l.isEmpty()) continue;
            md.append("| ")
                    .append(kind)
                    .append(" | ")
                    .append(l.size())
                    .append(" | ")
                    .append(sec(pct(l, 50)))
                    .append(" | ")
                    .append(sec(pct(l, 95)))
                    .append(" | ")
                    .append(sec(pct(l, 100)))
                    .append(" |\n");
        }

        md.append("\n## 3. LLM 호출(단계별)\n\n");
        md.append(
                "| 단계 | 호출 | 시도 1/2/3 | 받아들여짐 | 검사 거절(추정) | 제공자 실패 | finish_reason | 출력 토큰 p50·최대 | 지연 p50·p95·최대 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        Map<LlmCall, Verdict> verdictByCall = new LinkedHashMap<>();
        for (Turn t : turns) {
            List<Verdict> v = verdicts(t, rejections);
            for (int i = 0; i < t.llmCalls.size(); i++)
                verdictByCall.put(t.llmCalls.get(i), v.get(i));
        }
        for (AiOperation op :
                List.of(
                        AiOperation.CAUSE_ANALYSIS,
                        AiOperation.RESPONSE_GENERATION,
                        AiOperation.RESPONSE_EVALUATION)) {
            List<LlmCall> ofOp = calls.stream().filter(c -> c.operation() == op).toList();
            if (ofOp.isEmpty()) continue;
            Map<Integer, Integer> attempts = new TreeMap<>();
            Map<String, Integer> finish = new TreeMap<>();
            Map<String, Integer> failures = new TreeMap<>();
            int accepted = 0;
            int rejected = 0;
            for (LlmCall c : ofOp) {
                attempts.merge(c.attempt(), 1, Integer::sum);
                finish.merge(String.valueOf(c.finishReason()), 1, Integer::sum);
                Verdict v = verdictByCall.get(c);
                if (c.failureType() != null)
                    failures.merge(c.failureType().name(), 1, Integer::sum);
                else if (v != null && v.kind().startsWith("REJECTED")) rejected++;
                else accepted++;
            }
            List<Long> out =
                    ofOp.stream()
                            .filter(c -> c.outputTokens() != null)
                            .map(c -> (long) c.outputTokens())
                            .toList();
            List<Long> lat = ofOp.stream().map(LlmCall::wallMs).toList();
            md.append("| ")
                    .append(op)
                    .append(" | ")
                    .append(ofOp.size())
                    .append(" | ")
                    .append(attempts.getOrDefault(1, 0))
                    .append("/")
                    .append(attempts.getOrDefault(2, 0))
                    .append("/")
                    .append(attempts.getOrDefault(3, 0))
                    .append(" | ")
                    .append(accepted)
                    .append(" | ")
                    .append(rejected)
                    .append(" | ")
                    .append(failures.isEmpty() ? "0" : failures.toString())
                    .append(" | ")
                    .append(finish)
                    .append(" | ")
                    .append(out.isEmpty() ? "-" : pct(out, 50) + "·" + pct(out, 100))
                    .append(" | ")
                    .append(sec(pct(lat, 50)))
                    .append("·")
                    .append(sec(pct(lat, 95)))
                    .append("·")
                    .append(sec(pct(lat, 100)))
                    .append(" |\n");
        }
        Map<String, Integer> reasons = new TreeMap<>();
        verdictByCall.forEach(
                (c, v) -> {
                    if (v.kind().startsWith("REJECTED"))
                        reasons.merge(c.operation() + " · " + v.detail(), 1, Integer::sum);
                });
        if (!reasons.isEmpty()) {
            md.append(
                    "\n검사 거절(추정)의 출력 모양 — 팀 코드가 실제 이유를 남기지 않아 출력 원문으로 추정:\n\n| 단계 · 출력 | 수 |\n|---|---|\n");
            reasons.forEach(
                    (k, v) ->
                            md.append("| ")
                                    .append(cell(k, 200))
                                    .append(" | ")
                                    .append(v)
                                    .append(" |\n"));
        }
        Map<String, Integer> decisions = new TreeMap<>();
        verdictByCall.forEach(
                (c, v) -> {
                    if (v.kind().equals("EVALUATION")) decisions.merge(v.detail(), 1, Integer::sum);
                });
        if (!decisions.isEmpty()) md.append("\n평가 결정: ").append(decisions).append("\n");

        md.append("\n## 4. 불변식 위반\n\n");
        int violationCount = 0;
        for (Run r : runs) {
            for (Turn t : r.turns) {
                for (String v : violations(t)) {
                    violationCount++;
                    md.append("- ")
                            .append(r.label())
                            .append(" 턴 ")
                            .append(t.index)
                            .append(": ")
                            .append(v)
                            .append("\n");
                }
            }
            if (r.lastTurnNumber != null
                    && r.lastTurnNumber >= MAXIMUM_TURN_COUNT
                    && !"COMPLETED".equals(r.finalActivityStatus)) {
                violationCount++;
                md.append("- ")
                        .append(r.label())
                        .append(": 최대 턴(")
                        .append(MAXIMUM_TURN_COUNT)
                        .append(")에 닿았는데 활동이 ")
                        .append(r.finalActivityStatus)
                        .append("\n");
            }
            long empty =
                    r.turns.stream()
                            .filter(
                                    t ->
                                            "NOTICE_UNAVAILABLE".equals(t.status)
                                                    && t.messages.isEmpty())
                            .count();
            if (empty > 0)
                md.append("- (관찰) ")
                        .append(r.label())
                        .append(": 빈 안내 NOTICE_UNAVAILABLE ")
                        .append(empty)
                        .append("번 — 아동 화면에 보낼 문장이 없음(notices 미설정)\n");
        }
        if (violationCount == 0) md.append("- 없음\n");

        if (!stt.isEmpty()) {
            md.append("\n## 5. STT 호출\n\n| # | 지연 | 결과 | 신뢰도 | 실패 |\n|---|---|---|---|---|\n");
            for (SttCall c : stt) {
                md.append("| ")
                        .append(c.seq())
                        .append(" | ")
                        .append(sec(c.wallMs()))
                        .append(" | ")
                        .append(cell(nz(c.text()), 60))
                        .append(" | ")
                        .append(
                                c.confidence() == null
                                        ? "-"
                                        : String.format(Locale.ROOT, "%.2f", c.confidence()))
                        .append(" | ")
                        .append(
                                c.failureType() == null
                                        ? ""
                                        : c.failureType() + " " + nz(c.providerErrorCode()))
                        .append(" |\n");
            }
        }

        md.append("\n## 6. 턴 기록\n");
        for (Run r : runs) {
            md.append("\n### ")
                    .append(r.label())
                    .append(" (시나리오 ")
                    .append(nz(r.scenarioLevel))
                    .append(")\n\n");
            for (Map<String, Object> m : r.openingMessages)
                md.append("- 시작: ").append(cell(String.valueOf(m.get("text")), 200)).append("\n");
            md.append("\n| # | 입력 | 결과 | 지연 | AI 메시지 | 호출 |\n|---|---|---|---|---|---|\n");
            for (Turn t : r.turns) {
                List<Verdict> v = verdicts(t, rejections);
                List<String> callLabels = new ArrayList<>();
                for (int i = 0; i < t.llmCalls.size(); i++) {
                    LlmCall c = t.llmCalls.get(i);
                    callLabels.add(shortOp(c.operation()) + c.attempt() + ":" + v.get(i).label());
                }
                String text =
                        t.messages.stream()
                                .map(
                                        m ->
                                                nz(String.valueOf(m.get("speakerRole")))
                                                        + "/"
                                                        + m.get("purpose")
                                                        + " "
                                                        + m.get("text"))
                                .reduce((a, b) -> a + " ⏎ " + b)
                                .orElse("");
                md.append("| ")
                        .append(t.index)
                        .append(" | ")
                        .append(cell(t.input, 40))
                        .append(" | ")
                        .append(t.httpStatus)
                        .append(" ")
                        .append(statusKey(t))
                        .append("/")
                        .append(nz(t.action))
                        .append(" | ")
                        .append(sec(t.latencyMs))
                        .append(" | ")
                        .append(cell(text, 160))
                        .append(" | ")
                        .append(cell(String.join(" → ", callLabels), 400))
                        .append(" |\n");
            }
        }
        return md.toString();
    }

    private static String statusKey(Turn t) {
        if (t.status != null) return t.status;
        return t.errorCode != null ? "ERROR:" + t.errorCode : "-";
    }

    private static String shortOp(AiOperation op) {
        return switch (op) {
            case CAUSE_ANALYSIS -> "CA";
            case RESPONSE_GENERATION -> "RG";
            case RESPONSE_EVALUATION -> "RE";
            default -> op.name();
        };
    }

    static String field(String content, String name) {
        JsonNode node = tree(content);
        if (node == null) return null;
        return node.path(name).asString(null);
    }

    static JsonNode tree(String content) {
        if (content == null) return null;
        String text = content.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int fence = text.lastIndexOf("```");
            if (firstLine > 0 && fence > firstLine)
                text = text.substring(firstLine + 1, fence).strip();
        }
        try {
            JsonNode node = JSON.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJsonl(Path file, List<Object> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Object row : rows) sb.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static long pct(List<Long> values, int p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String sec(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    private static void row(StringBuilder md, String key, String value) {
        md.append("| ").append(key).append(" | ").append(value).append(" |\n");
    }

    private static String nz(String value) {
        return value == null || "null".equals(value) ? "-" : value;
    }

    private static String cell(String value, int max) {
        if (value == null) return "";
        String v = value.replace("|", "\\|").replace("\r", " ").replace("\n", " ⏎ ");
        if (v.isEmpty()) return "(빈 칸)";
        if (v.isBlank()) return "(공백)";
        int cp = v.codePointCount(0, v.length());
        if (cp > max) v = v.substring(0, v.offsetByCodePoints(0, max)) + "…(" + cp + "자)";
        return v;
    }
}
