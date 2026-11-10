package com.neuringo.neuringobe.ops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 워크플로우 한 번의 실행 기록. 값은 모두 고정 코드·횟수·시간·토큰 수다.
 *
 * @param path 이 실행이 지나간 흐름의 종류
 * @param endedBy 한 번에 통과하지 못했을 때 그 원인이 된 단계
 * @param reason 그 단계가 낸 판정·실패 코드
 * @param stageMs 구간별 자체 시간(ms)
 */
public record AgentRun(
        UUID runId,
        String workflow,
        Path path,
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
        List<Span> spans,
        List<Step> steps) {

    /** NORMAL 한 번에 통과, RETRIED 다시 돌아 정상 결과, GUIDED 설계된 안내로 끝남, FAILED 오류로 끝남. */
    public enum Path {
        NORMAL,
        RETRIED,
        GUIDED,
        FAILED
    }

    public enum Kind {
        CALL,
        CHECK,
        RESULT
    }

    public enum Status {
        PASSED,
        REJECTED,
        FAILED
    }

    /** 시간 구간 하나. 부모·자식 관계로 어느 구간 안에서 시간이 쓰였는지 본다. */
    public record Span(
            int id,
            int parent,
            String stage,
            String operation,
            int attempt,
            long startMs,
            long durationMs,
            long selfMs,
            String outcome) {}

    /**
     * 흐름의 한 단계.
     *
     * @param declaredNext 판정이 지목한 다음 단계
     * @param ledTo 실제로 이어진 다음 단계. 마지막 단계면 최종 결과 코드
     */
    public record Step(
            int seq,
            Kind kind,
            String step,
            int attempt,
            Status status,
            String verdict,
            List<String> codes,
            String declaredNext,
            String ledTo,
            long atMs,
            long durationMs,
            String model,
            String promptVersion,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens) {}
}
