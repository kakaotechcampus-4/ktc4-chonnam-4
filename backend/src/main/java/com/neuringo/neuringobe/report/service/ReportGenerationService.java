package com.neuringo.neuringobe.report.service;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.observation.AgentRunTrace;
import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder;
import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder.Workflow;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.report.LearningReportResult;
import com.neuringo.neuringobe.ai.application.report.RetryingLearningReportGenerator;
import com.neuringo.neuringobe.ai.application.report.StructuredLearningReportSteps;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.report.application.ReportNarrative;
import com.neuringo.neuringobe.report.config.ReportProperties;
import com.neuringo.neuringobe.report.repository.LearningReportStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리포트 2단계. PENDING 리포트의 생성 루프 입력으로 AI 요약을 만들고 COMPLETED 나 FAILED 로 바꾼다.
 *
 * <p>LLM 호출은 길어서(평가 p95 45초) 아동 역할극 턴 스레드가 아닌 생성 전용 스레드에서 돈다. 트랜잭션 없이 DB 문장 하나씩 바꾸고, 상태 변경은 모두
 * PENDING 을 조건으로 건다. 그래서 같은 리포트를 두 작업이 맡아도 먼저 끝난 결과 하나만 남는다.
 *
 * <p>LLM 제공자가 없으면(AI_CHAT_PROVIDER=none) 바로 FAILED(AI_UNAVAILABLE)로 둔다. 지표·난이도·추천은 1단계에서 이미 저장돼 있다.
 * 바퀴 기록에는 결과·실패 코드·점수만 남기고 초안 문장은 남기지 않는다.
 */
@Service
public class ReportGenerationService {
    private static final Logger log = LoggerFactory.getLogger(ReportGenerationService.class);

    private final LearningReportStore reports;
    private final ObjectProvider<LlmProvider> llm;
    private final LearningReportPromptFactory prompts;
    private final RetryingLearningReportGenerator generator;
    private final ThreadPoolExecutor workers;
    private final ReportProperties properties;
    private final JsonMapper json;
    private final Clock clock;
    private final ObjectProvider<AgentRunRecorder> runs;

    public ReportGenerationService(
            LearningReportStore reports,
            ObjectProvider<LlmProvider> llm,
            ObjectProvider<AgentRunRecorder> runs,
            LearningReportPromptFactory prompts,
            RetryingLearningReportGenerator generator,
            @Qualifier("reportGenerationWorkers") ThreadPoolExecutor workers,
            ReportProperties properties,
            JsonMapper json,
            Clock clock) {
        this.reports = reports;
        this.llm = llm;
        this.runs = runs;
        this.prompts = prompts;
        this.generator = generator;
        this.workers = workers;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /** 생성 스레드에 넣는다. 큐가 차 있으면 PENDING 으로 두고 재처리 작업에 맡긴다. */
    public void submit(UUID reportId) {
        try {
            workers.execute(() -> generate(reportId));
        } catch (RejectedExecutionException full) {
            log.warn("리포트 생성 대기열이 가득 차 나중에 다시 넣습니다: reportId={}", reportId);
        }
    }

    void generate(UUID reportId) {
        var job = reports.claim(reportId, clock.instant()).orElse(null);
        if (job == null) {
            return;
        }
        UUID runId = UUID.randomUUID();
        var recorder = runs.getIfAvailable(() -> AgentRunRecorder.NONE);
        if (job.attemptCount() > properties.maxAttempts()) {
            reports.fail(reportId, "GENERATION_STALLED", "[]", clock.instant());
            try (var run = AgentRunTrace.open(recorder, runId, Workflow.LEARNING_REPORT)) {
                run.failed("FAILED", "GENERATION_STALLED");
            }
            return;
        }
        LlmProvider provider = llm.getIfAvailable();
        if (provider == null) {
            reports.fail(reportId, "AI_UNAVAILABLE", "[]", clock.instant());
            try (var run = AgentRunTrace.open(recorder, runId, Workflow.LEARNING_REPORT)) {
                run.failed("FAILED", "AI_UNAVAILABLE");
            }
            return;
        }
        // 실행 기록에는 바퀴별 판정과 호출 시간·토큰만 남는다. 리포트 번호·아동 번호와 초안 문장은 넣지 않는다.
        try (var run = AgentRunTrace.open(recorder, runId, Workflow.LEARNING_REPORT)) {
            var input = json.readValue(job.llmInputJson(), LearningReportInput.class);
            var trace =
                    new AiTraceContext(
                            runId,
                            job.activityId(),
                            null,
                            job.childId(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
            var result =
                    generator.generate(
                            input,
                            new StructuredLearningReportSteps(
                                    new StructuredLlmExecutor(provider), prompts, trace));
            String rounds = json.writeValueAsString(result.rounds());
            if (result instanceof LearningReportResult.Completed completed) {
                reports.complete(
                        reportId,
                        completed.draft().summary(),
                        json.writeValueAsString(ReportNarrative.from(completed.draft())),
                        rounds,
                        clock.instant());
                run.completed("COMPLETED");
                log.info("리포트 생성 완료: reportId={} rounds={}", reportId, completed.rounds().size());
            } else if (result instanceof LearningReportResult.Failed failed) {
                reports.fail(reportId, failed.reason().name(), rounds, clock.instant());
                run.failed("FAILED", failed.reason().name());
                log.warn(
                        "리포트 생성 실패: reportId={} reason={} operation={}",
                        reportId,
                        failed.reason(),
                        failed.operation());
            }
        } catch (RuntimeException unexpected) {
            // 다음 재처리에서 다시 시도한다. 시도 횟수를 넘기면 GENERATION_STALLED 로 끝난다.
            log.warn(
                    "리포트 생성 중 예외가 나 PENDING 으로 둡니다: reportId={} type={}",
                    reportId,
                    unexpected.getClass().getSimpleName());
        }
    }

    /** 재처리 작업이 부른다. 오래 멈춘 PENDING 을 다시 넣는다. */
    public void resubmitStale() {
        var olderThan = clock.instant().minus(properties.staleAfter());
        List<LearningReportStore.Stale> stale = reports.findStalePending(olderThan, 20);
        for (var report : stale) {
            submit(report.reportId());
        }
    }
}
