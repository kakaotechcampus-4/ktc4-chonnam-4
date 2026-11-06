package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.PreparedPrompt;
import com.neuringo.neuringobe.ai.application.prompt.ReportRevision;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import java.util.Objects;

/** 프롬프트 v1 과 출력 검사를 거쳐 LLM 을 부른다. 리포트 하나를 만들 때마다 그 활동의 추적 문맥으로 새로 만든다. */
public final class StructuredLearningReportSteps implements LearningReportSteps {

    private final StructuredLlmExecutor llm;
    private final LearningReportPromptFactory prompts;
    private final AiTraceContext trace;

    public StructuredLearningReportSteps(
            StructuredLlmExecutor llm, LearningReportPromptFactory prompts, AiTraceContext trace) {
        this.llm = Objects.requireNonNull(llm);
        this.prompts = Objects.requireNonNull(prompts);
        this.trace = Objects.requireNonNull(trace);
    }

    @Override
    public AiCallResult<ReportDraft> draft(
            LearningReportInput input,
            ReportRevision revision,
            AiAttemptContext attempts,
            int stageAttempt) {
        PreparedPrompt<ReportDraft> prompt =
                prompts.generation(trace, attempts, stageAttempt, input, revision);
        return llm.execute(prompt.request(), prompt.parser());
    }

    @Override
    public AiCallResult<ReportReview> review(
            LearningReportInput input,
            ReportDraft draft,
            AiAttemptContext attempts,
            int stageAttempt) {
        PreparedPrompt<ReportReview> prompt =
                prompts.review(trace, attempts, stageAttempt, input, draft);
        return llm.execute(prompt.request(), prompt.parser());
    }
}
