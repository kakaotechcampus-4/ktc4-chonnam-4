package com.neuringo.neuringobe.ops.domain;

import static com.neuringo.neuringobe.ai.application.observation.RoleplayTiming.Stage.LLM_CALL;
import static com.neuringo.neuringobe.ai.application.observation.RoleplayTiming.Stage.STT;
import static com.neuringo.neuringobe.ai.application.observation.RoleplayTiming.Stage.TTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.observation.AgentRunTrace;
import com.neuringo.neuringobe.ai.application.observation.RoleplayTiming;
import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder;
import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder.Workflow;
import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.ops.domain.AgentRun.Kind;
import com.neuringo.neuringobe.ops.domain.AgentRun.Path;
import com.neuringo.neuringobe.ops.domain.AgentRun.Status;
import com.neuringo.neuringobe.ops.domain.AgentRun.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 실행 중에 나온 사건이 "단계 → 판정 → 실제로 이어진 다음 단계" 흐름으로 조립되는지 확인한다. 사건은 손으로 만들지 않고 실제 관측 장치(RoleplayTiming)를
 * 거쳐 만든다. 가짜 호출은 0ms 로 끝나므로, 순서가 시각이 아니라 구간 번호로 정해지는지도 함께 확인된다.
 */
class AgentRunAssemblerTest {
    private static final UUID RUN = UUID.fromString("7c1f3a52-0d6b-4f0e-9a51-2b8f6f3c9d10");
    private static final Instant STARTED = Instant.parse("2026-11-10T03:00:00Z");

    @Test
    void 한_번에_통과한_역할극_턴은_NORMAL_이고_단계가_순서대로_이어진다() {
        var run =
                roleplay(
                        () -> {
                            call(STT, AiOperation.SPEECH_TRANSCRIPTION, 1, ok(0, 0));
                            judged(
                                    AiOperation.INPUT_VALIDATION,
                                    "ANALYZABLE",
                                    true,
                                    null,
                                    List.of());
                            judged(AiOperation.CAUSE_ANALYSIS, null, null, null, List.of());
                            judged(AiOperation.RESPONSE_GENERATION, null, null, null, List.of());
                            judged(AiOperation.RESPONSE_EVALUATION, "PASS", true, null, List.of());
                            call(TTS, AiOperation.SPEECH_SYNTHESIS, 1, ok(0, 0));
                            resolved("APPROVED", true, false, List.of(), null, "RESPONSE_DELIVERY");
                        },
                        end("VOICE", 200, "DELIVERED"));

        assertThat(run.path()).isEqualTo(Path.NORMAL);
        assertThat(run.endedBy()).isNull();
        assertThat(run.reason()).isNull();
        assertThat(run.inputType()).isEqualTo("VOICE");
        assertThat(run.aiCalls()).isEqualTo(6);
        assertThat(run.repeatedCalls()).isZero();
        assertThat(run.inputTokens()).isEqualTo(4 * 120);
        assertThat(run.outputTokens()).isEqualTo(4 * 30);
        assertThat(run.steps())
                .extracting(Step::step)
                .containsExactly(
                        "SPEECH_TRANSCRIPTION",
                        "INPUT_VALIDATION",
                        "CAUSE_ANALYSIS",
                        "RESPONSE_GENERATION",
                        "RESPONSE_EVALUATION",
                        "SPEECH_SYNTHESIS",
                        "TURN_RESOLUTION",
                        "RESULT");
        assertThat(run.steps()).extracting(Step::status).containsOnly(Status.PASSED);
        // 각 단계의 ledTo 는 바로 다음 단계, 마지막 판정은 최종 결과를 가리킨다.
        assertThat(run.steps())
                .extracting(Step::ledTo)
                .containsExactly(
                        "INPUT_VALIDATION",
                        "CAUSE_ANALYSIS",
                        "RESPONSE_GENERATION",
                        "RESPONSE_EVALUATION",
                        "SPEECH_SYNTHESIS",
                        "TURN_RESOLUTION",
                        "DELIVERED",
                        null);
        Step evaluation = run.steps().get(4);
        assertThat(evaluation.verdict()).isEqualTo("PASS");
        assertThat(evaluation.model()).isEqualTo("gpt-5.4-nano");
        assertThat(evaluation.promptVersion()).isEqualTo("roleplay-response-evaluation/v3");
        assertThat(run.steps().getLast().kind()).isEqualTo(Kind.RESULT);
        assertThat(run.steps().getLast().verdict()).isEqualTo("DELIVERED");
        assertThat(run.spans()).isNotEmpty();
        assertThat(run.stageMs()).containsKey("LLM_CALL");
    }

    @Test
    void 검수가_다시_만들라고_하면_RETRIED_이고_그_판정이_다음_생성으로_이어진다() {
        var run =
                roleplay(
                        () -> {
                            judged(
                                    AiOperation.INPUT_VALIDATION,
                                    "ANALYZABLE",
                                    true,
                                    null,
                                    List.of());
                            judged(AiOperation.CAUSE_ANALYSIS, null, null, null, List.of());
                            judged(AiOperation.RESPONSE_GENERATION, null, null, null, List.of());
                            judged(
                                    AiOperation.RESPONSE_EVALUATION,
                                    "REGENERATE",
                                    false,
                                    "RESPONSE_GENERATION",
                                    List.of("GOAL_HINT_LEAK", "TOO_LONG"));
                            // 후보가 바뀌면 호출의 시도 번호는 다시 1 이다.
                            judged(AiOperation.RESPONSE_GENERATION, null, null, null, List.of());
                            judged(AiOperation.RESPONSE_EVALUATION, "PASS", true, null, List.of());
                            resolved("APPROVED", true, false, List.of(), null, "RESPONSE_DELIVERY");
                        },
                        end("TEXT", 200, "DELIVERED"));

        assertThat(run.path()).isEqualTo(Path.RETRIED);
        assertThat(run.endedBy()).isEqualTo("RESPONSE_EVALUATION");
        assertThat(run.reason()).isEqualTo("REGENERATE");
        assertThat(run.repeatedCalls()).isEqualTo(2);

        Step rejected = run.steps().get(3);
        assertThat(rejected.step()).isEqualTo("RESPONSE_EVALUATION");
        assertThat(rejected.attempt()).isEqualTo(1);
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        assertThat(rejected.verdict()).isEqualTo("REGENERATE");
        assertThat(rejected.codes()).containsExactly("GOAL_HINT_LEAK", "TOO_LONG");
        assertThat(rejected.declaredNext()).isEqualTo("RESPONSE_GENERATION");
        assertThat(rejected.ledTo()).isEqualTo("RESPONSE_GENERATION");

        Step regenerated = run.steps().get(4);
        assertThat(regenerated.step()).isEqualTo("RESPONSE_GENERATION");
        assertThat(regenerated.attempt()).isEqualTo(2);
        assertThat(regenerated.status()).isEqualTo(Status.PASSED);
        assertThat(run.steps().get(5).attempt()).isEqualTo(2);
        assertThat(run.steps().get(5).verdict()).isEqualTo("PASS");
    }

    @Test
    void 음성_신뢰도_미달로_다시_말해_달라고_끝나면_GUIDED_이고_원인은_음성_인식이다() {
        var run =
                roleplay(
                        () -> {
                            call(STT, AiOperation.SPEECH_TRANSCRIPTION, 1, ok(0, 0));
                            resolved(
                                    "INPUT_REJECTED",
                                    false,
                                    false,
                                    List.of("LOW_CONFIDENCE"),
                                    null,
                                    "NOTICE_INPUT_QUALITY");
                        },
                        end("VOICE", 200, "REINPUT_REQUIRED"));

        assertThat(run.path()).isEqualTo(Path.GUIDED);
        assertThat(run.endedBy()).isEqualTo("SPEECH_TRANSCRIPTION");
        assertThat(run.reason()).isEqualTo("LOW_CONFIDENCE");
        Step resolution = run.steps().get(1);
        assertThat(resolution.kind()).isEqualTo(Kind.CHECK);
        assertThat(resolution.status()).isEqualTo(Status.REJECTED);
        assertThat(resolution.declaredNext()).isEqualTo("NOTICE_INPUT_QUALITY");
        assertThat(resolution.ledTo()).isEqualTo("REINPUT_REQUIRED");
        assertThat(run.steps().getLast().status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void 안전_판정으로_멈춘_턴은_GUIDED_이고_원인은_입력_판정이다() {
        var run =
                roleplay(
                        () -> {
                            judged(AiOperation.INPUT_VALIDATION, "UNSAFE", false, null, List.of());
                            resolved("INPUT_UNSAFE", false, false, List.of(), null, "SAFETY_STOP");
                        },
                        end("TEXT", 200, "STOPPED"));

        assertThat(run.path()).isEqualTo(Path.GUIDED);
        assertThat(run.endedBy()).isEqualTo("INPUT_VALIDATION");
        assertThat(run.reason()).isEqualTo("INPUT_UNSAFE");
        assertThat(run.steps().getFirst().verdict()).isEqualTo("UNSAFE");
        assertThat(run.steps().getFirst().ledTo()).isEqualTo("TURN_RESOLUTION");
    }

    @Test
    void 생성_호출이_한도까지_실패하면_FAILED_이고_원인_단계와_사유가_남는다() {
        var run =
                roleplay(
                        () -> {
                            judged(
                                    AiOperation.INPUT_VALIDATION,
                                    "ANALYZABLE",
                                    true,
                                    null,
                                    List.of());
                            judged(AiOperation.CAUSE_ANALYSIS, null, null, null, List.of());
                            for (int attempt = 1; attempt <= 3; attempt++)
                                call(
                                        LLM_CALL,
                                        AiOperation.RESPONSE_GENERATION,
                                        attempt,
                                        failed(AiFailureType.TIMEOUT));
                            resolved(
                                    "LIMIT_REACHED",
                                    false,
                                    true,
                                    List.of("SAFE_FALLBACK"),
                                    AiOperation.RESPONSE_GENERATION,
                                    "NOTICE_SAFE_FALLBACK");
                        },
                        end("TEXT", 200, "REINPUT_REQUIRED"));

        assertThat(run.path()).isEqualTo(Path.FAILED);
        assertThat(run.endedBy()).isEqualTo("RESPONSE_GENERATION");
        assertThat(run.reason()).isEqualTo("LIMIT_REACHED");
        assertThat(run.steps().subList(2, 5))
                .allSatisfy(
                        step -> {
                            assertThat(step.step()).isEqualTo("RESPONSE_GENERATION");
                            assertThat(step.status()).isEqualTo(Status.FAILED);
                            assertThat(step.verdict()).isEqualTo("TIMEOUT");
                        });
        assertThat(run.steps().subList(2, 5)).extracting(Step::attempt).containsExactly(1, 2, 3);
        assertThat(run.steps().getLast().status()).isEqualTo(Status.FAILED);
    }

    @Test
    void 출력_형식이_틀리면_그_호출이_거절로_남고_같은_단계가_다시_돈다() {
        var run =
                roleplay(
                        () -> {
                            call(LLM_CALL, AiOperation.CAUSE_ANALYSIS, 1, ok(100, 20));
                            RoleplayTiming.note(
                                    "VALIDATION",
                                    AiDiagnostics.fields(
                                            "operation",
                                            AiOperation.CAUSE_ANALYSIS,
                                            "attempt",
                                            1,
                                            "outcome",
                                            "INVALID_OUTPUT_FORMAT"));
                            call(LLM_CALL, AiOperation.CAUSE_ANALYSIS, 2, ok(100, 20));
                            RoleplayTiming.note(
                                    "VALIDATION",
                                    AiDiagnostics.fields(
                                            "operation",
                                            AiOperation.CAUSE_ANALYSIS,
                                            "attempt",
                                            2,
                                            "outcome",
                                            "VALID"));
                        },
                        end("TEXT", 200, "DELIVERED"));

        assertThat(run.path()).isEqualTo(Path.RETRIED);
        assertThat(run.steps().get(0).status()).isEqualTo(Status.REJECTED);
        assertThat(run.steps().get(0).verdict()).isEqualTo("INVALID_OUTPUT_FORMAT");
        assertThat(run.steps().get(0).ledTo()).isEqualTo("CAUSE_ANALYSIS");
        assertThat(run.steps().get(1).status()).isEqualTo(Status.PASSED);
        assertThat(run.steps().get(1).verdict()).isEqualTo("VALID");
    }

    @Test
    void 서버_오류로_끝난_요청은_결과가_없어도_FAILED_로_남는다() {
        var end = AiDiagnostics.fields("input", "TEXT", "http_status", 500, "completion", "ERROR");
        var run = roleplay(() -> {}, end);

        assertThat(run.path()).isEqualTo(Path.FAILED);
        assertThat(run.result()).isEqualTo("HTTP_500");
        assertThat(run.steps()).hasSize(1);
    }

    @Test
    void 리포트는_규칙_검사와_통과_기준_판정까지_바퀴_순서대로_남는다() {
        var recorder = new Collector();
        try (var trace = AgentRunTrace.open(recorder, RUN, Workflow.LEARNING_REPORT)) {
            judged(AiOperation.REPORT_GENERATION, null, null, null, List.of());
            decided("REPORT_RULE_CHECK", 1, "RULES_REJECTED", false, false, List.of("TOO_LONG"));
            judged(AiOperation.REPORT_GENERATION, null, null, null, List.of());
            decided("REPORT_RULE_CHECK", 2, "RULES_PASSED", true, false, List.of());
            judged(AiOperation.REPORT_REVIEW, "PASS", true, null, List.of());
            decided("REPORT_QUALITY_GATE", 2, "PASSED", true, false, List.of());
            trace.completed("COMPLETED");
        }
        var run = recorder.assembled();

        assertThat(run.workflow()).isEqualTo("LEARNING_REPORT");
        assertThat(run.path()).isEqualTo(Path.RETRIED);
        assertThat(run.endedBy()).isEqualTo("REPORT_RULE_CHECK");
        assertThat(run.reason()).isEqualTo("RULES_REJECTED");
        assertThat(run.steps())
                .extracting(Step::step)
                .containsExactly(
                        "REPORT_GENERATION",
                        "REPORT_RULE_CHECK",
                        "REPORT_GENERATION",
                        "REPORT_RULE_CHECK",
                        "REPORT_REVIEW",
                        "REPORT_QUALITY_GATE",
                        "RESULT");
        assertThat(run.steps().get(1).codes()).containsExactly("TOO_LONG");
        assertThat(run.steps().get(1).ledTo()).isEqualTo("REPORT_GENERATION");
        assertThat(run.steps().get(2).attempt()).isEqualTo(2);
    }

    @Test
    void 리포트가_품질_한도에_걸려_실패하면_FAILED_이고_사유가_남는다() {
        var recorder = new Collector();
        try (var trace = AgentRunTrace.open(recorder, RUN, Workflow.LEARNING_REPORT)) {
            judged(AiOperation.REPORT_GENERATION, null, null, null, List.of());
            decided("REPORT_RULE_CHECK", 1, "RULES_PASSED", true, false, List.of());
            judged(AiOperation.REPORT_REVIEW, "REVISE", false, null, List.of("UNGROUNDED_CLAIM"));
            decided(
                    "REPORT_QUALITY_GATE",
                    1,
                    "REVIEW_REJECTED",
                    false,
                    false,
                    List.of("UNGROUNDED_CLAIM"));
            trace.failed("FAILED", "QUALITY_LIMIT_REACHED");
        }
        var run = recorder.assembled();

        assertThat(run.path()).isEqualTo(Path.FAILED);
        assertThat(run.result()).isEqualTo("FAILED");
        assertThat(run.reason()).isEqualTo("QUALITY_LIMIT_REACHED");
        assertThat(run.endedBy()).isEqualTo("REPORT_QUALITY_GATE");
    }

    @Test
    void 끝내지_않고_닫힌_실행은_ABORTED_로_남는다() {
        var recorder = new Collector();
        try (var trace = AgentRunTrace.open(recorder, RUN, Workflow.SCENARIO_GENERATION)) {
            call(LLM_CALL, AiOperation.SCENARIO_GENERATION, 1, ok(300, 900));
        }
        var run = recorder.assembled();

        assertThat(run.path()).isEqualTo(Path.FAILED);
        assertThat(run.result()).isEqualTo("ABORTED");
        assertThat(run.reason()).isEqualTo("UNEXPECTED_ERROR");
    }

    @Test
    void 코드_형식이_아닌_값은_저장하지_않는다() {
        var run =
                roleplay(
                        () -> {
                            call(LLM_CALL, AiOperation.RESPONSE_EVALUATION, 1, ok(10, 10));
                            RoleplayTiming.note(
                                    "VALIDATION",
                                    AiDiagnostics.fields(
                                            "operation",
                                            AiOperation.RESPONSE_EVALUATION,
                                            "attempt",
                                            1,
                                            "outcome",
                                            "VALID",
                                            "verdict",
                                            "아이가 한 말을 그대로 옮긴 문장",
                                            "accepted",
                                            false,
                                            "next",
                                            "다음 단계 설명 문장",
                                            "codes",
                                            List.of("TOO_LONG", "민수가 울었어요", "TOO_LONG")));
                        },
                        end("TEXT", 200, "REINPUT_REQUIRED"));

        Step step = run.steps().getFirst();
        assertThat(step.verdict()).isEqualTo("VALID");
        assertThat(step.declaredNext()).isNull();
        assertThat(step.codes()).containsExactly("TOO_LONG", "OTHER");
        assertThat(step.status()).isEqualTo(Status.REJECTED);
    }

    // --- 실제 관측 장치를 거쳐 사건을 만드는 도우미 ---

    private static AgentRun roleplay(Runnable turn, Map<String, Object> end) {
        var recorder = new Collector();
        recorder.begin(RUN, Workflow.ROLEPLAY_TURN);
        var timing =
                RoleplayTiming.begin(RUN, AiDiagnostics.NONE, event -> recorder.event(RUN, event));
        turn.run();
        timing.finish("ERROR".equals(end.get("completion")), end.get("result"), null);
        recorder.end(RUN, end);
        return recorder.assembled();
    }

    private static Map<String, Object> end(String input, int status, String result) {
        return AiDiagnostics.fields(
                "input",
                input,
                "http_status",
                status,
                "completion",
                "COMPLETE",
                "result",
                result,
                "replayed",
                false,
                "elapsed_ms",
                1234L);
    }

    /** LLM 호출 한 번과 그 출력 검사 결과. verdict 가 null 이면 판정 출력이 아닌 단계다. */
    private static void judged(
            AiOperation operation,
            String verdict,
            Boolean accepted,
            String next,
            List<String> codes) {
        call(LLM_CALL, operation, 1, ok(120, 30));
        var data = AiDiagnostics.fields("operation", operation, "attempt", 1, "outcome", "VALID");
        if (verdict != null) {
            data.put("verdict", verdict);
            data.put("accepted", accepted);
            data.put("next", next);
            data.put("codes", codes);
        }
        RoleplayTiming.note("VALIDATION", data);
    }

    private static void decided(
            String step,
            int attempt,
            String verdict,
            boolean accepted,
            boolean error,
            List<String> codes) {
        RoleplayTiming.note(
                "DECISION",
                AiDiagnostics.fields(
                        "step",
                        step,
                        "attempt",
                        attempt,
                        "verdict",
                        verdict,
                        "accepted",
                        accepted,
                        "error",
                        error,
                        "codes",
                        codes));
    }

    private static void resolved(
            String verdict,
            boolean accepted,
            boolean error,
            List<String> codes,
            AiOperation operation,
            String next) {
        RoleplayTiming.note(
                "DECISION",
                AiDiagnostics.fields(
                        "step",
                        "TURN_RESOLUTION",
                        "verdict",
                        verdict,
                        "accepted",
                        accepted,
                        "error",
                        error,
                        "codes",
                        codes,
                        "operation",
                        operation,
                        "next",
                        next));
    }

    private static void call(
            RoleplayTiming.Stage stage,
            AiOperation operation,
            int attempt,
            AiCallResult<String> result) {
        RoleplayTiming.ai(stage, operation, attempt, () -> result);
    }

    private static AiCallResult<String> ok(int inputTokens, int outputTokens) {
        return new AiCallResult.Success<>("ignored", metadata(inputTokens, outputTokens));
    }

    private static AiCallResult<String> failed(AiFailureType type) {
        return new AiCallResult.Failure<>(new AiFailure(type, null), metadata(0, 0));
    }

    private static AiCallMetadata metadata(int inputTokens, int outputTokens) {
        return new AiCallMetadata(
                RUN,
                AiOperation.RESPONSE_EVALUATION,
                "openai-compatible",
                "gpt-5.4-nano",
                "roleplay-response-evaluation/v3",
                "evaluation/v2",
                "policy-v1",
                840,
                1,
                inputTokens,
                outputTokens,
                "stop");
    }

    private static final class Collector implements AgentRunRecorder {
        private final List<Map<String, Object>> events = new ArrayList<>();
        private Workflow workflow;
        private Map<String, Object> end;

        @Override
        public void begin(UUID runId, Workflow workflow) {
            this.workflow = workflow;
        }

        @Override
        public void event(UUID runId, Map<String, Object> event) {
            events.add(event);
        }

        @Override
        public void end(UUID runId, Map<String, Object> result) {
            this.end = result;
        }

        private AgentRun assembled() {
            return AgentRunAssembler.assemble(RUN, workflow, STARTED, events, false, end);
        }
    }
}
