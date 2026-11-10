package com.neuringo.neuringobe.ai.application.observation;

import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder;
import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder.Workflow;
import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import java.util.UUID;

/**
 * HTTP 필터를 거치지 않는 워크플로우(리포트·시나리오 생성) 한 번을 실행 기록으로 묶는다. 연 동안 같은 스레드의 AI 호출과 판정 사건(RoleplayTiming)이 이
 * 실행에 붙는다.
 *
 * <p>finish 를 부르지 않고 닫히면 예외로 끝난 것으로 보고 ABORTED 로 남긴다. 기록 실패는 워크플로우로 전파하지 않는다.
 */
public final class AgentRunTrace implements AutoCloseable {
    private final AgentRunRecorder recorder;
    private final UUID runId;
    private final RoleplayTiming.Session session;
    private final long startedNanos = System.nanoTime();
    private boolean finished;

    private AgentRunTrace(AgentRunRecorder recorder, UUID runId, RoleplayTiming.Session session) {
        this.recorder = recorder;
        this.runId = runId;
        this.session = session;
    }

    public static AgentRunTrace open(AgentRunRecorder recorder, UUID runId, Workflow workflow) {
        try {
            recorder.begin(runId, workflow);
        } catch (RuntimeException failure) {
            /* 기록을 열지 못해도 워크플로우는 그대로 돈다. */
        }
        return new AgentRunTrace(
                recorder,
                runId,
                RoleplayTiming.observe(runId, event -> recorder.event(runId, event)));
    }

    /** 정상 결과로 끝났다. result 는 COMPLETED·GENERATED 같은 고정 코드다. */
    public void completed(String result) {
        finish(result, false, null);
    }

    /** 오류로 끝났다. reason 은 실패 사유 코드다. */
    public void failed(String result, String reason) {
        finish(result, true, reason);
    }

    private void finish(String result, boolean failed, String reason) {
        if (finished) return;
        finished = true;
        session.finish(failed, result, null);
        try {
            recorder.end(
                    runId,
                    AiDiagnostics.fields(
                            "result",
                            result,
                            "failed",
                            failed,
                            "reason",
                            reason,
                            "elapsed_ms",
                            (System.nanoTime() - startedNanos) / 1_000_000));
        } catch (RuntimeException failure) {
            /* 기록 저장 실패는 결과를 바꾸지 않는다. */
        }
    }

    @Override
    public void close() {
        finish("ABORTED", true, "UNEXPECTED_ERROR");
    }
}
