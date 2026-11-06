package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.report.LearningReportResult;
import com.neuringo.neuringobe.ai.application.report.RetryingLearningReportGenerator;
import com.neuringo.neuringobe.ai.application.report.StructuredLearningReportSteps;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import java.util.Objects;

/**
 * 완료 활동 하나의 리포트를 만든다(03 VS-014 "완료 기록 집계 → 퀴즈·역할극 근거 요약 → 기술 오류 분리 → 강사용 문구 생성").
 *
 * <p>지표 계산(LearningMetrics), 입력 조립(LearningReportInputAssembler), 생성·평가
 * 루프(RetryingLearningReportGenerator)를 순서대로 부르기만 한다. 저장과 트랜잭션은 이 클래스를 부르는 서비스의 책임이다.
 */
public final class LearningReportComposer {

    private final StructuredLlmExecutor llm;
    private final LearningReportPromptFactory prompts;
    private final RetryingLearningReportGenerator generator;
    private final LearningReportInputAssembler assembler;

    public LearningReportComposer(
            StructuredLlmExecutor llm,
            LearningReportPromptFactory prompts,
            RetryingLearningReportGenerator generator,
            LearningReportInputAssembler assembler) {
        this.llm = Objects.requireNonNull(llm);
        this.prompts = Objects.requireNonNull(prompts);
        this.generator = Objects.requireNonNull(generator);
        this.assembler = Objects.requireNonNull(assembler);
    }

    public ComposedReport compose(ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        LearningMetrics metrics = LearningMetrics.from(request.evidence());
        LearningReportInput input = assembler.assemble(request.evidence(), metrics);
        AiTraceContext trace =
                new AiTraceContext(
                        request.requestId(),
                        request.activityId(),
                        request.classId(),
                        request.childId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        LearningReportResult result =
                generator.generate(input, new StructuredLearningReportSteps(llm, prompts, trace));
        return new ComposedReport(metrics, result);
    }
}
