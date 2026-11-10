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
 * @param flows 같은 단계 순서·판정으로 흘러간 실행끼리 묶은 흐름 패턴. 많이 일어난 순서다
 * @param diagnosis 바로 앞 같은 길이 기간과 비교해 규칙으로 뽑은 자동 진단
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
        List<StageTime> stages,
        List<Flow> flows,
        Diagnosis diagnosis) {

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

    /** recentRunIds 는 이 원인으로 끝난 최근 실행 번호(최대 3개)다. */
    public record Ending(
            String workflow,
            String path,
            String result,
            String endedBy,
            String reason,
            long count,
            List<java.util.UUID> recentRunIds) {}

    public record StageTime(String stage, long avgMs, long p95Ms) {}

    /**
     * 흐름 패턴 하나.
     *
     * @param outcome SUCCESS(정상 결과로 끝남) 또는 FAILURE
     * @param steps 최종 결과 앞까지의 단계와 판정(같은 단계가 다시 돌면 다시 나온다)
     * @param recentRunIds 이 흐름으로 끝난 최근 실행 번호(최대 3개). 역할극 턴은 X-Trace-Id 와 같다
     */
    public record Flow(
            String workflow,
            String outcome,
            String path,
            String result,
            List<FlowStep> steps,
            long count,
            long p50Ms,
            List<java.util.UUID> recentRunIds) {}

    public record FlowStep(String step, String status, String verdict) {}

    /**
     * 자동 진단. 이번 기간과 바로 앞 같은 길이 기간(previousFrom~previousTo)을 비교한다.
     *
     * @param previousRuns 앞 기간 실행 수. 적으면 비교하는 규칙은 건너뛴다
     */
    public record Diagnosis(
            Instant previousFrom,
            Instant previousTo,
            long previousRuns,
            Double failureRate,
            Double previousFailureRate,
            List<Finding> findings) {}

    /**
     * 진단 하나. 문장은 화면이 kind·step·code 로 만든다.
     *
     * @param kind FAILURE_RATE·NEW_FAILURE_FLOW·STEP_BLOCKED·REASON_SURGE·LATENCY
     * @param severity CRITICAL·WARNING·INFO
     * @param unit current·previous·threshold 의 단위. RATE(0~1)·COUNT·MS
     * @param previous 앞 기간 값. 비교할 표본이 없으면 null
     * @param sampleRunIds 볼 만한 실행 번호(최대 3개)
     */
    public record Finding(
            String kind,
            String severity,
            String workflow,
            String step,
            String code,
            double current,
            Double previous,
            double threshold,
            String unit,
            List<java.util.UUID> sampleRunIds) {}
}
