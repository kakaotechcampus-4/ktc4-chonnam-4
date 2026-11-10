package com.neuringo.neuringobe.ops.dto;

import com.neuringo.neuringobe.ops.domain.AgentRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 실행 기록 한 건. 목록에서는 steps·spans·stageMs 를 비워 보낸다.
 *
 * <p>역할극 턴의 runId 는 그 요청의 X-Trace-Id 와 같다. 브라우저에서 본 번호로 바로 찾을 수 있다.
 */
public record AgentRunResponse(
        UUID runId,
        String workflow,
        String path,
        String result,
        String endedBy,
        String reason,
        String inputType,
        Integer httpStatus,
        boolean replayed,
        Instant startedAt,
        long durationMs,
        int aiCalls,
        int repeatedCalls,
        int inputTokens,
        int outputTokens,
        boolean truncated,
        Map<String, Long> stageMs,
        List<AgentRun.Span> spans,
        List<AgentRun.Step> steps) {

    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(
                run.runId(),
                run.workflow(),
                run.path().name(),
                run.result(),
                run.endedBy(),
                run.reason(),
                run.inputType(),
                run.httpStatus(),
                run.replayed(),
                run.startedAt(),
                run.durationMs(),
                run.aiCalls(),
                run.repeatedCalls(),
                run.inputTokens(),
                run.outputTokens(),
                run.truncated(),
                run.stageMs(),
                run.spans(),
                run.steps());
    }
}
