package com.neuringo.neuringobe.ops.dto;

import java.time.Instant;
import java.util.List;

/**
 * 기간 안의 실행 기록 요약. 대시보드 첫 화면이 이 응답 하나로 그려진다.
 *
 * @param series 시간 구간별 흐름 종류 건수. 실행이 없던 구간도 0 으로 채워 온다
 * @param transitions 단계가 낸 판정과 실제로 이어진 다음 단계의 건수
 * @param codes 거절·실패 사유 코드 건수
 * @param endings 한 번에 통과하지 못한 실행이 무엇 때문에 어떻게 끝났는지
 * @param stages 역할극 턴의 구간별 자체 시간
 */
public record AgentRunOverviewResponse(
        Window window,
        Totals totals,
        List<Bucket> series,
        List<WorkflowStat> workflows,
        List<StepStat> steps,
        List<Transition> transitions,
        List<CodeCount> codes,
        List<Ending> endings,
        List<StageTime> stages) {

    public record Window(Instant from, Instant to, int hours, int bucketMinutes) {}

    public record Totals(
            long runs,
            long normal,
            long retried,
            long guided,
            long failed,
            long p50Ms,
            long p95Ms,
            long aiCalls,
            long inputTokens,
            long outputTokens) {}

    public record Bucket(
            Instant at,
            long normal,
            long retried,
            long guided,
            long failed,
            long p50Ms,
            long p95Ms) {}

    public record WorkflowStat(
            String workflow,
            long runs,
            long normal,
            long retried,
            long guided,
            long failed,
            long p50Ms,
            long p95Ms) {}

    public record StepStat(
            String workflow,
            String step,
            long calls,
            long passed,
            long rejected,
            long failed,
            long avgMs,
            long p95Ms,
            long inputTokens,
            long outputTokens) {}

    public record Transition(
            String workflow, String from, String status, String verdict, String to, long count) {}

    public record CodeCount(String workflow, String step, String code, long count) {}

    public record Ending(
            String workflow,
            String path,
            String result,
            String endedBy,
            String reason,
            long count) {}

    public record StageTime(String stage, long avgMs, long p95Ms) {}
}
