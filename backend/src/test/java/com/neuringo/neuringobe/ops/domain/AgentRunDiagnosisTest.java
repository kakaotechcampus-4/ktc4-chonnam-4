package com.neuringo.neuringobe.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ops.domain.AgentRunDiagnosis.Snapshot;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Ending;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Finding;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Flow;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.FlowStep;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.StepStat;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Totals;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.WorkflowStat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 자동 진단 규칙. 걸려야 할 때 걸리고, 표본이 적거나 비교할 앞 기간이 없을 때는 판단하지 않는지 본다. */
class AgentRunDiagnosisTest {
    private static final long BUDGET = 60_000;
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID C = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    private static final List<FlowStep> LOW_CONFIDENCE =
            List.of(
                    new FlowStep("SPEECH_TRANSCRIPTION", "PASSED", "OK"),
                    new FlowStep("TURN_RESOLUTION", "REJECTED", "INPUT_REJECTED"));
    private static final List<FlowStep> TIMEOUT =
            List.of(
                    new FlowStep("CAUSE_ANALYSIS", "PASSED", "VALID"),
                    new FlowStep("RESPONSE_EVALUATION", "FAILED", "TIMEOUT"),
                    new FlowStep("TURN_RESOLUTION", "FAILED", "LIMIT_REACHED"));
    private static final List<FlowStep> REGENERATE =
            List.of(
                    new FlowStep("RESPONSE_EVALUATION", "REJECTED", "REGENERATE"),
                    new FlowStep("RESPONSE_GENERATION", "PASSED", "VALID"),
                    new FlowStep("RESPONSE_EVALUATION", "PASSED", "PASS"));

    @Test
    void 실행이_적으면_실패율이_높아도_판단하지_않는다() {
        var now = snapshot(totals(10, 2, 0, 6, 2), List.of(), List.of(), List.of(), 20_000);

        assertThat(AgentRunDiagnosis.diagnose(now, empty(), BUDGET, List.of())).isEmpty();
    }

    @Test
    void 실패율이_기준을_넘으면_앞_기간이_없어도_알린다() {
        var now =
                snapshot(
                        totals(40, 20, 6, 10, 4),
                        List.of(),
                        List.of(),
                        List.of(flow("GUIDED", "REINPUT_REQUIRED", LOW_CONFIDENCE, 10, A)),
                        20_000);

        var finding = only(AgentRunDiagnosis.diagnose(now, empty(), BUDGET, List.of()));

        assertThat(finding.kind()).isEqualTo("FAILURE_RATE");
        assertThat(finding.severity()).isEqualTo("WARNING");
        assertThat(finding.current()).isEqualTo(0.35);
        assertThat(finding.previous()).isNull();
        assertThat(finding.sampleRunIds()).containsExactly(A);
    }

    @Test
    void 실패율이_앞_기간보다_5퍼센트포인트_넘게_오르면_알린다() {
        var before = snapshot(totals(40, 36, 2, 2, 0), List.of(), List.of(), List.of(), 20_000);
        var now = snapshot(totals(40, 30, 4, 6, 0), List.of(), List.of(), List.of(), 20_000);

        var finding = only(AgentRunDiagnosis.diagnose(now, before, BUDGET, List.of()));

        assertThat(finding.kind()).isEqualTo("FAILURE_RATE");
        assertThat(finding.current()).isEqualTo(0.15);
        assertThat(finding.previous()).isEqualTo(0.05);
    }

    @Test
    void 앞_기간에_없던_실패_흐름이_나타나면_어느_단계에서_갈렸는지와_함께_알린다() {
        var before =
                snapshot(
                        totals(40, 38, 0, 2, 0),
                        List.of(),
                        List.of(),
                        List.of(flow("GUIDED", "REINPUT_REQUIRED", LOW_CONFIDENCE, 2, A)),
                        20_000);
        var now =
                snapshot(
                        totals(40, 37, 0, 2, 1),
                        List.of(),
                        List.of(),
                        List.of(
                                flow("GUIDED", "REINPUT_REQUIRED", LOW_CONFIDENCE, 2, A),
                                flow("FAILED", "RETRY_REQUIRED", TIMEOUT, 1, B)),
                        20_000);

        var finding = only(AgentRunDiagnosis.diagnose(now, before, BUDGET, List.of()));

        assertThat(finding.kind()).isEqualTo("NEW_FAILURE_FLOW");
        assertThat(finding.severity()).isEqualTo("WARNING");
        assertThat(finding.step()).isEqualTo("RESPONSE_EVALUATION");
        assertThat(finding.code()).isEqualTo("TIMEOUT");
        assertThat(finding.sampleRunIds()).containsExactly(B);
    }

    @Test
    void 호출_단계의_거절_실패_비율이_높으면_가장_많은_판정과_예시_실행을_붙인다() {
        var now =
                snapshot(
                        totals(40, 28, 12, 0, 0),
                        List.of(),
                        List.of(step("RESPONSE_EVALUATION", 52, 40, 12, 0)),
                        List.of(flow("RETRIED", "DELIVERED", REGENERATE, 12, C)),
                        20_000);

        var finding = only(AgentRunDiagnosis.diagnose(now, empty(), BUDGET, List.of()));

        assertThat(finding.kind()).isEqualTo("STEP_BLOCKED");
        assertThat(finding.severity()).isEqualTo("WARNING");
        assertThat(finding.step()).isEqualTo("RESPONSE_EVALUATION");
        assertThat(finding.code()).isEqualTo("REGENERATE");
        assertThat(finding.current()).isEqualTo(12.0 / 52);
        assertThat(finding.sampleRunIds()).containsExactly(C);
    }

    @Test
    void 호출이_적은_단계는_거절이_많아도_판단하지_않는다() {
        var now =
                snapshot(
                        totals(40, 36, 4, 0, 0),
                        List.of(),
                        List.of(step("RESPONSE_EVALUATION", 6, 2, 4, 0)),
                        List.of(),
                        20_000);

        assertThat(AgentRunDiagnosis.diagnose(now, empty(), BUDGET, List.of())).isEmpty();
    }

    @Test
    void 같은_원인으로_끝난_실행이_앞_기간의_두_배를_넘으면_알린다() {
        // 실패율은 두 기간 모두 10% 라 실패율 진단은 나오지 않고, 그 안의 신뢰도 미달만 두 배가 됐다.
        var before =
                snapshot(
                        totals(40, 36, 0, 4, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        20_000,
                        List.of(ending("GUIDED", "SPEECH_TRANSCRIPTION", "LOW_CONFIDENCE", 2, A)));
        var now =
                snapshot(
                        totals(80, 72, 0, 8, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        20_000,
                        List.of(ending("GUIDED", "SPEECH_TRANSCRIPTION", "LOW_CONFIDENCE", 8, B)));

        var finding = only(AgentRunDiagnosis.diagnose(now, before, BUDGET, List.of()));

        assertThat(finding.kind()).isEqualTo("REASON_SURGE");
        assertThat(finding.severity()).isEqualTo("WARNING");
        assertThat(finding.step()).isEqualTo("SPEECH_TRANSCRIPTION");
        assertThat(finding.code()).isEqualTo("LOW_CONFIDENCE");
        assertThat(finding.current()).isEqualTo(8);
        assertThat(finding.previous()).isEqualTo(2);
        assertThat(finding.sampleRunIds()).containsExactly(B);
    }

    @Test
    void 앞_기간_표본이_적으면_비교하는_규칙은_건너뛴다() {
        var before = snapshot(totals(5, 5, 0, 0, 0), List.of(), List.of(), List.of(), 20_000);
        var now =
                snapshot(
                        totals(40, 34, 0, 5, 1),
                        List.of(),
                        List.of(),
                        List.of(flow("FAILED", "RETRY_REQUIRED", TIMEOUT, 1, B)),
                        20_000,
                        List.of(ending("GUIDED", "SPEECH_TRANSCRIPTION", "LOW_CONFIDENCE", 5, A)));

        assertThat(AgentRunDiagnosis.diagnose(now, before, BUDGET, List.of())).isEmpty();
    }

    @Test
    void 단계_진단이_짚은_판정은_원인_급증으로_한_번_더_내지_않는다() {
        var before =
                snapshot(
                        totals(40, 38, 2, 0, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        20_000,
                        List.of(ending("RETRIED", "RESPONSE_EVALUATION", "REGENERATE", 2, A)));
        var now =
                snapshot(
                        totals(40, 28, 12, 0, 0),
                        List.of(),
                        List.of(step("RESPONSE_EVALUATION", 52, 40, 12, 0)),
                        List.of(flow("RETRIED", "DELIVERED", REGENERATE, 12, C)),
                        20_000,
                        List.of(ending("RETRIED", "RESPONSE_EVALUATION", "REGENERATE", 12, C)));

        assertThat(AgentRunDiagnosis.diagnose(now, before, BUDGET, List.of()))
                .extracting(Finding::kind)
                .containsExactly("STEP_BLOCKED");
    }

    @Test
    void 역할극_턴_p95_가_제한_시간의_80퍼센트를_넘으면_주의_넘으면_심각이다() {
        var near = snapshot(totals(40, 40, 0, 0, 0), List.of(), List.of(), List.of(), 50_000);
        var over = snapshot(totals(40, 40, 0, 0, 0), List.of(), List.of(), List.of(), 61_000);
        var fine = snapshot(totals(40, 40, 0, 0, 0), List.of(), List.of(), List.of(), 40_000);

        var warning = only(AgentRunDiagnosis.diagnose(near, empty(), BUDGET, List.of(A, B)));
        assertThat(warning.kind()).isEqualTo("LATENCY");
        assertThat(warning.severity()).isEqualTo("WARNING");
        assertThat(warning.threshold()).isEqualTo(48_000);
        assertThat(warning.sampleRunIds()).containsExactly(A, B);
        assertThat(only(AgentRunDiagnosis.diagnose(over, empty(), BUDGET, List.of())).severity())
                .isEqualTo("CRITICAL");
        assertThat(AgentRunDiagnosis.diagnose(fine, empty(), BUDGET, List.of())).isEmpty();
    }

    @Test
    void 심각한_진단이_먼저_온다() {
        var now =
                snapshot(
                        totals(40, 18, 0, 2, 20),
                        List.of(),
                        List.of(step("RESPONSE_EVALUATION", 52, 40, 12, 0)),
                        List.of(),
                        20_000);

        assertThat(AgentRunDiagnosis.diagnose(now, empty(), BUDGET, List.of()))
                .extracting(f -> f.kind() + ":" + f.severity())
                .containsExactly("FAILURE_RATE:CRITICAL", "STEP_BLOCKED:WARNING");
    }

    // --- 집계 값 만들기 ---

    private static Snapshot empty() {
        return snapshot(totals(0, 0, 0, 0, 0), List.of(), List.of(), List.of(), 0);
    }

    private static Snapshot snapshot(
            Totals totals,
            List<Object> unused,
            List<StepStat> steps,
            List<Flow> flows,
            long p95Ms) {
        return snapshot(totals, unused, steps, flows, p95Ms, List.of());
    }

    private static Snapshot snapshot(
            Totals totals,
            List<Object> unused,
            List<StepStat> steps,
            List<Flow> flows,
            long p95Ms,
            List<Ending> endings) {
        var roleplay =
                new WorkflowStat(
                        "ROLEPLAY_TURN",
                        totals.runs(),
                        totals.normal(),
                        totals.retried(),
                        totals.guided(),
                        totals.failed(),
                        p95Ms / 2,
                        p95Ms);
        return new Snapshot(
                totals, totals.runs() == 0 ? List.of() : List.of(roleplay), steps, endings, flows);
    }

    private static Totals totals(long runs, long normal, long retried, long guided, long failed) {
        return new Totals(runs, normal, retried, guided, failed, 0, 0, 0, 0, 0);
    }

    private static StepStat step(String step, long calls, long passed, long rejected, long failed) {
        return new StepStat("ROLEPLAY_TURN", step, calls, passed, rejected, failed, 0, 0, 0, 0);
    }

    private static Flow flow(
            String path, String result, List<FlowStep> steps, long count, UUID id) {
        String outcome = path.equals("NORMAL") || path.equals("RETRIED") ? "SUCCESS" : "FAILURE";
        return new Flow("ROLEPLAY_TURN", outcome, path, result, steps, count, 0, List.of(id));
    }

    private static Ending ending(String path, String endedBy, String reason, long count, UUID id) {
        return new Ending("ROLEPLAY_TURN", path, "RESULT", endedBy, reason, count, List.of(id));
    }

    private static Finding only(List<Finding> findings) {
        assertThat(findings).hasSize(1);
        return findings.getFirst();
    }
}
