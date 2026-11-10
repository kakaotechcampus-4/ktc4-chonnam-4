package com.neuringo.neuringobe.ops.domain;

import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder.Workflow;
import com.neuringo.neuringobe.ops.domain.AgentRun.Kind;
import com.neuringo.neuringobe.ops.domain.AgentRun.Path;
import com.neuringo.neuringobe.ops.domain.AgentRun.Span;
import com.neuringo.neuringobe.ops.domain.AgentRun.Status;
import com.neuringo.neuringobe.ops.domain.AgentRun.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 실행 중에 모인 사건(구간·판정·요약)을 저장할 실행 기록 한 건으로 바꾼다.
 *
 * <ul>
 *   <li>AI·음성 호출 구간 하나가 흐름의 한 단계가 되고, 그 호출의 출력 검사 결과(VALIDATION)는 같은 단계에 합친다.
 *   <li>코드가 내린 분기(DECISION)는 따로 한 단계가 된다.
 *   <li>각 단계에는 실제로 이어진 다음 단계(ledTo)를 붙인다. "어떤 판정 때문에 무엇이 다시 돌았는가"가 이 값이다.
 *   <li>마지막에 최종 결과 단계를 붙이고, 실행 전체를 NORMAL·RETRIED·GUIDED·FAILED 로 나눈다.
 * </ul>
 *
 * <p>문자열은 코드 형식(영문·숫자·일부 기호 64자 이내)만 통과시킨다. 모델이 실패 코드 자리에 문장을 넣어도 저장되지 않는다.
 */
public final class AgentRunAssembler {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:/@\\-]{1,64}");
    private static final Set<String> CALL_STAGES = Set.of("LLM_CALL", "STT", "TTS");
    private static final int MAX_STEPS = 120;
    private static final int MAX_CODES = 12;
    private static final Map<Workflow, String> NORMAL_RESULT =
            Map.of(
                    Workflow.ROLEPLAY_TURN, "DELIVERED",
                    Workflow.LEARNING_REPORT, "COMPLETED",
                    Workflow.SCENARIO_GENERATION, "GENERATED");

    private AgentRunAssembler() {}

    public static AgentRun assemble(
            UUID runId,
            Workflow workflow,
            Instant startedAt,
            List<Map<String, Object>> events,
            boolean overflowed,
            Map<String, Object> end) {
        var spans = new ArrayList<Span>();
        var drafts = new ArrayList<Draft>();
        var calls = new ArrayList<Draft>();
        Map<String, Object> summary = Map.of();
        var notes = new ArrayList<Map<String, Object>>();
        for (var event : events) {
            String kind = text(event.get("event"));
            if ("STAGE".equals(kind)) {
                var span = span(event);
                spans.add(span);
                if (CALL_STAGES.contains(span.stage())) {
                    var call = Draft.call(span, event);
                    calls.add(call);
                    drafts.add(call);
                }
            } else if ("SUMMARY".equals(kind)) summary = event;
            else if ("VALIDATION".equals(kind) || "DECISION".equals(kind)) notes.add(event);
        }
        calls.sort(Comparator.comparingInt(d -> d.position));
        for (var note : notes) {
            if ("VALIDATION".equals(text(note.get("event")))) {
                var call = callFor(calls, note);
                if (call != null) call.validate(note);
                else drafts.add(Draft.check(code(note.get("operation")), note, drafts.size()));
            } else drafts.add(Draft.check(code(note.get("step")), note, drafts.size()));
        }
        // 호출은 자기 구간 번호, 판정은 그때까지 열린 구간 수로 줄 세운다. 번호가 같으면 호출이 먼저다(판정은 그 호출 뒤에 나왔다).
        drafts.sort(
                Comparator.<Draft>comparingInt(d -> d.position)
                        .thenComparingInt(d -> d.kind == Kind.CALL ? 0 : 1)
                        .thenComparingInt(d -> d.order));
        // 호출의 시도 번호는 후보가 바뀌면 다시 1 부터 센다. 화면에는 "이 실행에서 몇 번째 호출인가"가 필요하므로 다시 매긴다.
        var seen = new LinkedHashMap<String, Integer>();
        for (var draft : drafts)
            if (draft.kind == Kind.CALL) draft.attempt = seen.merge(draft.step, 1, Integer::sum);
        if (drafts.size() > MAX_STEPS) {
            drafts.subList(MAX_STEPS, drafts.size()).clear();
            overflowed = true;
        }

        String result = code(end.get("result"));
        Integer httpStatus = end.get("http_status") instanceof Number n ? n.intValue() : null;
        if (result == null && httpStatus != null) result = "HTTP_" + httpStatus;
        if (result == null) result = "UNKNOWN";
        Path path = path(workflow, result, httpStatus, end, drafts);
        long duration =
                end.get("elapsed_ms") instanceof Number n
                        ? n.longValue()
                        : number(summary.get("duration_ms"));

        var steps = new ArrayList<Step>();
        for (int i = 0; i < drafts.size(); i++) {
            var draft = drafts.get(i);
            String ledTo = i + 1 < drafts.size() ? drafts.get(i + 1).step : result;
            steps.add(draft.step(i + 1, ledTo));
        }
        steps.add(
                new Step(
                        steps.size() + 1,
                        Kind.RESULT,
                        "RESULT",
                        0,
                        switch (path) {
                            case NORMAL, RETRIED -> Status.PASSED;
                            case GUIDED -> Status.REJECTED;
                            case FAILED -> Status.FAILED;
                        },
                        result,
                        List.of(),
                        null,
                        null,
                        duration,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null));

        Draft cause = cause(path, drafts);
        String reason = code(end.get("reason"));
        if (reason == null && cause != null) reason = cause.reason();
        spans.sort(Comparator.comparingLong(Span::startMs).thenComparingInt(Span::id));
        return new AgentRun(
                runId,
                workflow.name(),
                path,
                result,
                cause == null ? null : owner(cause, drafts),
                reason,
                code(end.get("input")),
                httpStatus,
                Boolean.TRUE.equals(end.get("replayed")),
                startedAt,
                Math.max(0, duration),
                calls.size(),
                calls.size() - (int) calls.stream().map(d -> d.step).distinct().count(),
                calls.stream().mapToInt(d -> d.inputTokens == null ? 0 : d.inputTokens).sum(),
                calls.stream().mapToInt(d -> d.outputTokens == null ? 0 : d.outputTokens).sum(),
                overflowed || Boolean.TRUE.equals(summary.get("truncated")),
                stageMs(summary.get("self_ms_by_stage")),
                List.copyOf(spans),
                List.copyOf(steps));
    }

    /** 같은 목적·시도 번호의 호출 가운데, 검사 결과가 아직 붙지 않았고 이 판정보다 먼저 시작한 마지막 호출. */
    private static Draft callFor(List<Draft> calls, Map<String, Object> note) {
        String operation = code(note.get("operation"));
        long attempt = number(note.get("attempt"));
        long position = number(note.get("after_span"));
        Draft found = null;
        for (var call : calls)
            if (!call.validated
                    && call.step.equals(operation)
                    && call.attempt == attempt
                    && call.position <= position) found = call;
        return found;
    }

    private static Path path(
            Workflow workflow,
            String result,
            Integer httpStatus,
            Map<String, Object> end,
            List<Draft> drafts) {
        boolean failed =
                Boolean.TRUE.equals(end.get("failed"))
                        || "ERROR".equals(end.get("completion"))
                        || (httpStatus != null && httpStatus >= 500)
                        || drafts.stream().anyMatch(d -> d.error);
        if (failed) return Path.FAILED;
        if (result.equals(NORMAL_RESULT.get(workflow)))
            return drafts.stream().allMatch(d -> d.status == Status.PASSED)
                    ? Path.NORMAL
                    : Path.RETRIED;
        if (workflow != Workflow.ROLEPLAY_TURN) return Path.FAILED;
        // 작업자가 모두 차 있었거나 내보낼 안내 문구가 없으면 아동은 응답을 받지 못한다.
        return result.equals("BUSY")
                        || result.equals("NOTICE_UNAVAILABLE")
                        || result.equals("UNKNOWN")
                ? Path.FAILED
                : Path.GUIDED;
    }

    /** 다시 돈 실행은 처음 거절된 단계를, 안내·오류로 끝난 실행은 마지막으로 통과하지 못한 단계를 원인으로 본다. */
    private static Draft cause(Path path, List<Draft> drafts) {
        if (path == Path.NORMAL) return null;
        var blocked = drafts.stream().filter(d -> d.status != Status.PASSED).toList();
        if (blocked.isEmpty()) return null;
        return path == Path.RETRIED ? blocked.getFirst() : blocked.getLast();
    }

    /**
     * 원인 단계의 이름. 턴 결과 판정처럼 앞 단계의 결과를 정리한 판정이면 그 판정이 가리킨 단계를, 가리킨 단계가 없으면 바로 앞 단계를 원인으로 돌린다. 예: 음성
     * 인식 뒤 신뢰도 미달로 끝난 턴의 원인은 음성 인식이다.
     */
    private static String owner(Draft cause, List<Draft> drafts) {
        if (cause.kind != Kind.CHECK) return cause.step;
        if (cause.operation != null) return cause.operation;
        int index = drafts.indexOf(cause);
        return "TURN_RESOLUTION".equals(cause.step) && index > 0
                ? drafts.get(index - 1).step
                : cause.step;
    }

    private static Span span(Map<String, Object> event) {
        return new Span(
                (int) number(event.get("span_id")),
                (int) number(event.get("parent_span_id")),
                code(event.get("stage")),
                code(event.get("operation")),
                (int) number(event.get("attempt")),
                number(event.get("start_ms")),
                number(event.get("duration_ms")),
                number(event.get("self_ms")),
                code(event.get("outcome")));
    }

    private static Map<String, Long> stageMs(Object value) {
        var stages = new LinkedHashMap<String, Long>();
        if (value instanceof Map<?, ?> map)
            map.forEach(
                    (key, ms) -> {
                        String stage = code(key);
                        if (stage != null && ms instanceof Number n)
                            stages.put(stage, n.longValue());
                    });
        return stages;
    }

    private static List<String> codes(Object value) {
        var codes = new LinkedHashSet<String>();
        if (value instanceof Iterable<?> items)
            for (var item : items) {
                if (codes.size() >= MAX_CODES) break;
                String code = code(item);
                codes.add(code == null ? "OTHER" : code);
            }
        return List.copyOf(codes);
    }

    /** 코드 형식이 아닌 값은 버린다. 글이 섞여 들어와도 저장하지 않기 위해서다. */
    private static String code(Object value) {
        String text = text(value);
        return text != null && CODE.matcher(text).matches() ? text : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static Integer count(Object value) {
        return value instanceof Number n ? Math.max(0, n.intValue()) : null;
    }

    private static final class Draft {
        Kind kind;
        String step;
        int attempt;
        long atMs;
        long durationMs;
        int position;
        int order;
        Status status = Status.PASSED;
        String verdict;
        List<String> codes = List.of();
        String declaredNext;
        String operation;
        boolean error;
        boolean validated;
        String model;
        String promptVersion;
        String finishReason;
        Integer inputTokens;
        Integer outputTokens;

        static Draft call(Span span, Map<String, Object> event) {
            var draft = new Draft();
            draft.kind = Kind.CALL;
            // 음성 인식·합성은 구간 이름(STT·TTS)보다 호출 목적 이름이 다른 단계와 같은 말로 읽힌다.
            draft.step = span.operation() == null ? span.stage() : span.operation();
            draft.attempt = span.attempt();
            draft.atMs = span.startMs();
            draft.durationMs = span.durationMs();
            draft.position = span.id();
            draft.model = code(event.get("model"));
            draft.promptVersion = code(event.get("prompt_version"));
            draft.finishReason = code(event.get("finish_reason"));
            draft.inputTokens = count(event.get("input_tokens"));
            draft.outputTokens = count(event.get("output_tokens"));
            String outcome = span.outcome() == null ? "UNKNOWN" : span.outcome();
            if (outcome.equals("COMPLETED")) draft.verdict = "OK";
            else {
                draft.status = Status.FAILED;
                draft.verdict =
                        outcome.startsWith("PROVIDER_FAILURE_")
                                ? outcome.substring("PROVIDER_FAILURE_".length())
                                : outcome;
            }
            return draft;
        }

        static Draft check(String step, Map<String, Object> note, int order) {
            var draft = new Draft();
            draft.kind = Kind.CHECK;
            draft.step = step == null ? "CHECK" : step;
            draft.attempt = (int) number(note.get("attempt"));
            draft.atMs = number(note.get("at_ms"));
            draft.position = (int) number(note.get("after_span"));
            draft.order = order;
            draft.judge(note);
            return draft;
        }

        /** 호출이 성공했을 때만 그 출력의 검사 결과를 얹는다. 호출 실패는 그대로 둔다. */
        void validate(Map<String, Object> note) {
            validated = true;
            if (status == Status.PASSED) judge(note);
        }

        private void judge(Map<String, Object> note) {
            String outcome = code(note.get("outcome"));
            String judged = code(note.get("verdict"));
            verdict = judged != null ? judged : outcome != null ? outcome : "OK";
            codes = AgentRunAssembler.codes(note.get("codes"));
            declaredNext = code(note.get("next"));
            operation = code(note.get("operation"));
            error = Boolean.TRUE.equals(note.get("error"));
            boolean invalid = outcome != null && !outcome.equals("VALID");
            if (error) status = Status.FAILED;
            else if (invalid || Boolean.FALSE.equals(note.get("accepted")))
                status = Status.REJECTED;
        }

        /** 입력 거절은 판정 이름(INPUT_REJECTED)보다 거절 사유(LOW_CONFIDENCE 등)가 원인을 말해 준다. */
        String reason() {
            return "INPUT_REJECTED".equals(verdict) && !codes.isEmpty()
                    ? codes.getFirst()
                    : verdict;
        }

        Step step(int seq, String ledTo) {
            return new Step(
                    seq,
                    kind,
                    step,
                    attempt,
                    status,
                    verdict,
                    codes,
                    declaredNext,
                    ledTo,
                    atMs,
                    durationMs,
                    model,
                    promptVersion,
                    finishReason,
                    inputTokens,
                    outputTokens);
        }
    }
}
