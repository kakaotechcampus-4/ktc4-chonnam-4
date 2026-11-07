package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.report.LearningReportResult;
import com.neuringo.neuringobe.ai.application.report.ReportQualityRule;
import com.neuringo.neuringobe.ai.application.report.ReportQualityRules;
import com.neuringo.neuringobe.ai.application.report.RetryingLearningReportGenerator;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import com.neuringo.neuringobe.report.application.ComposedReport;
import com.neuringo.neuringobe.report.application.LearningReportComposer;
import com.neuringo.neuringobe.report.application.LearningReportInputAssembler;
import com.neuringo.neuringobe.report.application.ReportRequest;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;

/**
 * 회귀 세트의 각 사례를 운영과 같은 경로(지표 → 입력 조립 → 프롬프트 v1 → 출력 검사 → 생성·평가 루프)로 돌리고, 통과한 리포트를 루프 규칙과 따로 한 번 더
 * 검사한다. 저장·HTTP 는 거치지 않는다.
 */
final class ReportEvalRunner {

    /** 사례 기대값과 별도로 모든 리포트에서 보는 평가 말투 낱말. 프롬프트가 금지한 것이다. */
    static final List<String> TONE_WORDS = List.of("잘했", "훌륭", "대단", "아쉬", "부족");

    /** 시스템이 숫자를 따로 보여 주므로 문장에 옮겨 적지 않아야 한다(프롬프트 규칙). */
    private static final Pattern COPIED_NUMBER = Pattern.compile("\\d+\\s*(%|퍼센트|개 중|문항 중)");

    private static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");

    private final ReportRegressionSet set;
    private final RecordingLlmProvider recorder;
    private final LearningReportComposer composer;
    private final LearningReportPromptFactory prompts;
    private final List<ReportQualityRule> rules = ReportQualityRules.defaults(ReportLimits.DEFAULT);

    ReportEvalRunner(ReportRegressionSet set, LlmProvider provider, ObjectMapper objectMapper) {
        this.set = Objects.requireNonNull(set);
        this.recorder = new RecordingLlmProvider(provider);
        this.prompts = new LearningReportPromptFactory(objectMapper, ReportLimits.DEFAULT);
        this.composer =
                new LearningReportComposer(
                        new StructuredLlmExecutor(recorder),
                        prompts,
                        new RetryingLearningReportGenerator(rules),
                        new LearningReportInputAssembler());
    }

    List<ReportRecord> run(
            int repeats,
            Predicate<ReportRegressionSet.Case> filter,
            Consumer<ReportRecord> progress) {
        List<ReportRecord> records = new ArrayList<>();
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (ReportRegressionSet.Case testCase : set.cases()) {
                if (!filter.test(testCase)) continue;
                ReportRecord record = runOne(testCase, repeat);
                records.add(record);
                progress.accept(record);
            }
        }
        return records;
    }

    private ReportRecord runOne(ReportRegressionSet.Case testCase, int repeat) {
        int from = recorder.size();
        long startedAt = System.nanoTime();
        ReportRequest request =
                new ReportRequest(
                        UUID.randomUUID(),
                        UUID.nameUUIDFromBytes(testCase.id().getBytes()),
                        null,
                        CHILD_ID,
                        testCase.evidence());
        ComposedReport report = composer.compose(request);
        long wallMs = (System.nanoTime() - startedAt) / 1_000_000;
        List<RecordingLlmProvider.Call> calls = recorder.since(from);
        LearningMetrics metrics = report.metrics();

        if (report.result() instanceof LearningReportResult.Completed completed) {
            LearningReportInput input =
                    new LearningReportInputAssembler().assemble(testCase.evidence(), metrics);
            return new ReportRecord(
                    testCase.id(),
                    testCase.purpose(),
                    repeat,
                    true,
                    null,
                    completed.rounds(),
                    calls,
                    formatFailures(calls, input),
                    wallMs,
                    check(testCase, completed.draft(), input),
                    completed.draft(),
                    completed.review().scores(),
                    metrics);
        }
        LearningReportResult.Failed failed = (LearningReportResult.Failed) report.result();
        LearningReportInput input =
                new LearningReportInputAssembler().assemble(testCase.evidence(), metrics);
        ReportDraft lastDraft = lastDraft(calls, input);
        List<String> lastIssues =
                lastDraft == null
                        ? List.of()
                        : rules.stream()
                                .flatMap(rule -> rule.check(lastDraft, input).stream())
                                .map(issue -> "LAST:" + issue.code() + "(" + issue.detail() + ")")
                                .toList();
        return new ReportRecord(
                testCase.id(),
                testCase.purpose(),
                repeat,
                false,
                failed.reason().name(),
                failed.rounds(),
                calls,
                formatFailures(calls, input),
                wallMs,
                lastIssues,
                lastDraft,
                null,
                metrics);
    }

    /** 실패한 사례를 분석하려고 마지막 초안 호출을 다시 파싱한다. 형식이 틀렸으면 null. */
    private ReportDraft lastDraft(
            List<RecordingLlmProvider.Call> calls, LearningReportInput input) {
        AiTraceContext trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        CHILD_ID,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        LlmOutputParser<ReportDraft> parser =
                prompts.generation(trace, new AiAttemptContext(0, 1, 0), 1, input, null).parser();
        for (int i = calls.size() - 1; i >= 0; i--) {
            RecordingLlmProvider.Call call = calls.get(i);
            if (call.operation() != AiOperation.REPORT_GENERATION || call.content() == null)
                continue;
            try {
                return parser.parse(call.content());
            } catch (InvalidLlmOutputException exception) {
                return null;
            }
        }
        return null;
    }

    /** 통과한 초안을 사례 기대값·평가 말투·숫자 옮겨 적기·루프 규칙으로 다시 본다. */
    private List<String> check(
            ReportRegressionSet.Case testCase, ReportDraft draft, LearningReportInput input) {
        List<String> failures = new ArrayList<>();
        Map<String, List<ReportItem>> sections =
                Map.of(
                        "observations", draft.observations(),
                        "effective_supports", draft.effectiveSupports(),
                        "difficult_points", draft.difficultPoints());
        for (String name : testCase.expect().nonEmpty()) {
            if (sections.getOrDefault(name, List.of()).isEmpty()) failures.add("EMPTY:" + name);
        }
        for (String name : testCase.expect().empty()) {
            if (!sections.getOrDefault(name, List.of()).isEmpty())
                failures.add("NOT_EMPTY:" + name);
        }
        List<String> texts =
                Stream.concat(Stream.of(draft.summary()), draft.items().map(ReportItem::text))
                        .toList();
        for (String word : testCase.expect().forbidden()) {
            if (texts.stream().anyMatch(text -> text.contains(word)))
                failures.add("FORBIDDEN:" + word);
        }
        for (String word : TONE_WORDS) {
            if (texts.stream().anyMatch(text -> text.contains(word))) failures.add("TONE:" + word);
        }
        if (texts.stream().anyMatch(text -> COPIED_NUMBER.matcher(text).find())) {
            failures.add("COPIED_NUMBER");
        }
        rules.stream()
                .flatMap(rule -> rule.check(draft, input).stream())
                .forEach(issue -> failures.add("RULE:" + issue.code()));
        return failures;
    }

    /** 호출 원문을 같은 출력 검사로 다시 파싱해 형식 실패를 센다. 루프는 형식 실패를 재시도로 넘기므로 따로 세야 보인다. */
    /** 형식 검사에 걸린 호출마다 "단계 | 이유 | 원문 앞부분" 한 줄. 회귀 세트가 합성 문장이라 원문을 남긴다. */
    private List<String> formatFailures(
            List<RecordingLlmProvider.Call> calls, LearningReportInput input) {
        AiTraceContext trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        CHILD_ID,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        AiAttemptContext attempts = new AiAttemptContext(0, 1, 1);
        LlmOutputParser<?> draftParser =
                prompts.generation(trace, attempts, 1, input, null).parser();
        LlmOutputParser<?> reviewParser =
                prompts.review(trace, attempts, 1, input, placeholderDraft()).parser();
        List<String> failures = new ArrayList<>();
        for (RecordingLlmProvider.Call call : calls) {
            if (call.content() == null) continue;
            LlmOutputParser<?> parser =
                    call.operation() == AiOperation.REPORT_REVIEW ? reviewParser : draftParser;
            try {
                parser.parse(call.content());
            } catch (InvalidLlmOutputException exception) {
                String cause =
                        exception.getCause() == null
                                ? ""
                                : " / "
                                        + exception
                                                .getCause()
                                                .getMessage()
                                                .lines()
                                                .findFirst()
                                                .orElse("");
                String content = call.content().replaceAll("\\s+", " ");
                failures.add(
                        call.operation()
                                + " | "
                                + exception.getMessage()
                                + cause
                                + " | "
                                + content.substring(0, Math.min(700, content.length())));
            }
        }
        return failures;
    }

    private static ReportDraft placeholderDraft() {
        return new ReportDraft("요약", List.of(), List.of(), List.of());
    }
}
