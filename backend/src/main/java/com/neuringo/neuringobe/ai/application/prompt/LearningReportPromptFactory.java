package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * 학습 리포트의 두 호출(초안 작성 → 초안 평가) 프롬프트를 만든다(03 VS-014).
 *
 * <p>시스템 프롬프트는 classpath 의 prompts/report/ 아래 초안 v3·평가 v4 파일이고, 사용자 메시지는 입력 JSON 하나다. 추적 식별자와 아동
 * 이름은 넣지 않는다. 재시도 횟수와 호출 순서는 RetryingLearningReportGenerator 가 정한다.
 */
public final class LearningReportPromptFactory {

    public static final String GENERATION_PROMPT_VERSION = "report-generation/v3";
    public static final String REVIEW_PROMPT_VERSION = "report-review/v4";

    public static final String DRAFT_SCHEMA_VERSION = "report-draft/v1";
    public static final String REVIEW_SCHEMA_VERSION = "report-review/v2";

    private final ObjectMapper objectMapper;
    private final ReportLimits limits;
    private final String generationSystemPrompt;
    private final String reviewSystemPrompt;

    public LearningReportPromptFactory(ObjectMapper objectMapper, ReportLimits limits) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.limits = Objects.requireNonNull(limits);
        this.generationSystemPrompt = PromptTemplates.load("report/v3/report-generation.md");
        this.reviewSystemPrompt = PromptTemplates.load("report/v4/report-review.md");
    }

    public ReportLimits limits() {
        return limits;
    }

    /** 초안 작성. revision 은 다시 쓸 때만 넣는다. */
    public PreparedPrompt<ReportDraft> generation(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            LearningReportInput input,
            ReportRevision revision) {
        Map<String, Object> message = inputSections(input);
        if (revision != null) {
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("previous_draft", draftSection(revision.previousDraft()));
            retry.put("failure_codes", revision.failureCodes());
            retry.put("revision_instruction", instructionSection(revision.instruction()));
            message.put("retry", retry);
        }
        LlmRequest request =
                new LlmRequest(
                        trace,
                        attempts,
                        AiOperation.REPORT_GENERATION,
                        currentAttempt,
                        generationSystemPrompt,
                        toJson(message),
                        GENERATION_PROMPT_VERSION,
                        DRAFT_SCHEMA_VERSION,
                        null);
        return new PreparedPrompt<>(request, LearningReportOutputParsers.draft(objectMapper));
    }

    /** 초안 평가. 평가 모델은 초안을 고쳐 쓰지 않는다. */
    public PreparedPrompt<ReportReview> review(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            LearningReportInput input,
            ReportDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        Map<String, Object> message = inputSections(input);
        message.put("draft", draftSection(draft));
        LlmRequest request =
                new LlmRequest(
                        trace,
                        attempts,
                        AiOperation.REPORT_REVIEW,
                        currentAttempt,
                        reviewSystemPrompt,
                        toJson(message),
                        REVIEW_PROMPT_VERSION,
                        REVIEW_SCHEMA_VERSION,
                        null);
        return new PreparedPrompt<>(request, LearningReportOutputParsers.review(objectMapper));
    }

    private Map<String, Object> inputSections(LearningReportInput input) {
        Objects.requireNonNull(input, "input must not be null");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(
                "evidence",
                input.evidence().stream()
                        .map(
                                item -> {
                                    Map<String, Object> entry = new LinkedHashMap<>();
                                    entry.put("id", item.id());
                                    entry.put("kind", item.kind().name());
                                    if (item.goalTitle() != null)
                                        entry.put("goal", item.goalTitle());
                                    entry.put("text", item.text());
                                    if (item.goalStatus() != null)
                                        entry.put("goal_status", item.goalStatus().name());
                                    return entry;
                                })
                        .toList());
        message.put("metrics", input.metrics());
        if (input.difficulty() != null) {
            Map<String, Object> difficulty = new LinkedHashMap<>();
            difficulty.put("decision_type", input.difficulty().decisionType());
            difficulty.put("scenario_level", input.difficulty().scenarioLevel());
            difficulty.put("reason_code", input.difficulty().reasonCode());
            message.put("difficulty", difficulty);
        }
        Map<String, Object> limitSection = new LinkedHashMap<>();
        limitSection.put("summary_max_chars", limits.summaryMaxChars());
        limitSection.put("item_max_chars", limits.itemMaxChars());
        limitSection.put("items_per_section_max", limits.itemsPerSectionMax());
        message.put("limits", limitSection);
        return message;
    }

    private static Map<String, Object> draftSection(ReportDraft draft) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("summary", draft.summary());
        section.put("observations", items(draft.observations()));
        section.put("effective_supports", items(draft.effectiveSupports()));
        section.put("difficult_points", items(draft.difficultPoints()));
        return section;
    }

    private static List<Map<String, Object>> items(List<ReportItem> items) {
        return items.stream()
                .map(
                        item -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("text", item.text());
                            entry.put("evidence_ids", item.evidenceIds());
                            return entry;
                        })
                .toList();
    }

    private static Map<String, Object> instructionSection(RevisionInstruction instruction) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("keep", instruction.keep());
        section.put("change", instruction.change());
        section.put("avoid", instruction.avoid());
        section.put("required", instruction.required());
        return section;
    }

    private String toJson(Map<String, Object> message) {
        return objectMapper.writeValueAsString(message);
    }
}
