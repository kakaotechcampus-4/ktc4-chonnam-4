package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RetryingRoleplayTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.StructuredRoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 회귀 세트의 각 턴을 운영과 같은 경로(프롬프트 v1 → 출력 검사 → RetryingRoleplayTurnExecutor 재시도)로 실행하고 기록한다.
 *
 * <p>입력 처리(개인정보 가림·안전 처리)는 운영 구현이 아직 없어서 세트의 canonical_utterance 로 대신한다. HTTP·저장·TTS 는 거치지 않는다.
 */
final class RoleplayEvalRunner {

    /** 프롬프트가 요구하는 응답 길이. 출력 검사는 80자에서 막고, 60자를 넘긴 응답은 따로 센다. */
    static final int SOFT_RESPONSE_LENGTH = 60;

    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID CLASS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID SCENARIO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e6");

    private final RegressionSet set;
    private final RecordingLlmProvider recorder;
    private final StructuredLlmExecutor llm;
    private final RoleplayPromptFactory prompts;
    private final Duration turnBudget;
    private final List<FormatSample> formatSamples = new ArrayList<>();

    RoleplayEvalRunner(
            RegressionSet set,
            LlmProvider provider,
            RoleplayPromptFactory prompts,
            Duration turnBudget) {
        this.set = Objects.requireNonNull(set);
        this.recorder = new RecordingLlmProvider(provider);
        this.llm = new StructuredLlmExecutor(recorder);
        this.prompts = Objects.requireNonNull(prompts);
        this.turnBudget = Objects.requireNonNull(turnBudget);
    }

    /** 고른 케이스를 repeats 번 돈다. 반복이 바깥 루프라 한 회차가 끝나야 다음 회차를 시작한다. */
    List<TurnRecord> run(
            int repeats, Predicate<RegressionSet.Case> filter, Consumer<TurnRecord> onTurn) {
        if (repeats < 1) throw new IllegalArgumentException("repeats must be at least 1");
        List<TurnRecord> records = new ArrayList<>();
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (RegressionSet.Case turn : set.cases()) {
                if (!filter.test(turn)) continue;
                TurnRecord record = runTurn(turn, repeat);
                records.add(record);
                onTurn.accept(record);
            }
        }
        return records;
    }

    /** 형식 실패 원문 샘플. 보고서 작성기가 format-failures.jsonl 로 따로 뺀다. */
    List<FormatSample> formatFailureSamples() {
        return List.copyOf(formatSamples);
    }

    TurnRecord runTurn(RegressionSet.Case turn, int repeat) {
        RoleplayTurnInput input = set.toInput(turn);
        UUID turnId = input.learnerTurn().turnId();
        AiTraceContext trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        ACTIVITY_ID,
                        CLASS_ID,
                        CHILD_ID,
                        SCENARIO_ID,
                        1,
                        sessionId(turn.suite()),
                        turnId,
                        null,
                        null);
        AtomicReference<UUID> analysisId = new AtomicReference<>();
        StructuredRoleplayTurnSteps steps =
                new StructuredRoleplayTurnSteps(
                        prompts,
                        llm,
                        trace,
                        input,
                        () -> {
                            UUID next = UUID.randomUUID();
                            analysisId.set(next);
                            return next;
                        });
        ObservedTurnSteps observed =
                new ObservedTurnSteps(steps, recorder, prompts, input, trace, analysisId::get);

        int mark = recorder.size();
        long startedAt = System.nanoTime();
        RoleplayTurnResult result =
                new RetryingRoleplayTurnExecutor()
                        .execute(turnId, observed, RoleplayTurnDeadline.start(turnBudget));
        long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
        List<RecordingLlmProvider.Call> calls = recorder.since(mark);

        observed.formatFailures()
                .forEach(
                        failure ->
                                formatSamples.add(
                                        new FormatSample(
                                                turn.id(),
                                                repeat,
                                                failure.operation().name(),
                                                failure.reason(),
                                                failure.content())));
        return toRecord(turn, repeat, result, totalMs, calls, observed);
    }

    /** format-failures.jsonl 한 줄. content 는 모델이 보낸 원문이다(회귀 세트 입력은 모두 합성 문장). */
    record FormatSample(
            String caseId, int repeat, String operation, String reason, String content) {}

    private TurnRecord toRecord(
            RegressionSet.Case turn,
            int repeat,
            RoleplayTurnResult result,
            long totalMs,
            List<RecordingLlmProvider.Call> calls,
            ObservedTurnSteps observed) {
        String outcome;
        String recovery = null;
        AnalysisResult analysis = observed.lastAnalysis();
        CandidateResponse delivered = null;
        switch (result) {
            case RoleplayTurnResult.Ready ready -> {
                outcome = "DELIVERED";
                analysis = ready.analysis();
                delivered = ready.candidate();
            }
            case RoleplayTurnResult.RecoveryRequired required -> {
                outcome = "RECOVERY";
                recovery = required.target() + "/" + required.operation() + "/" + required.reason();
            }
            case RoleplayTurnResult.TimedOut ignored -> outcome = "TIMED_OUT";
            default -> {
                outcome = "UNEXPECTED";
                recovery = result.getClass().getSimpleName();
            }
        }

        List<EvaluationResult> evaluations = observed.evaluations();
        boolean firstPass =
                !evaluations.isEmpty()
                        && evaluations.getFirst().decision() == EvaluationDecision.PASS;

        return new TurnRecord(
                turn.id(),
                turn.suite(),
                repeat,
                calls.stream()
                        .map(RecordingLlmProvider.Call::model)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null),
                outcome,
                recovery,
                firstPass,
                totalMs,
                calls.size(),
                calls.stream().mapToInt(c -> c.inputTokens() == null ? 0 : c.inputTokens()).sum(),
                calls.stream().mapToInt(c -> c.outputTokens() == null ? 0 : c.outputTokens()).sum(),
                calls.stream()
                        .map(
                                c ->
                                        new TurnRecord.StageCall(
                                                c.operation().name(),
                                                c.attempt(),
                                                c.wallMs(),
                                                c.inputTokens(),
                                                c.outputTokens(),
                                                c.finishReason(),
                                                c.failureType() == null
                                                        ? null
                                                        : c.failureType().name()))
                        .toList(),
                evaluations.stream().map(e -> e.decision().name()).toList(),
                evaluations.stream().flatMap(e -> e.failureCodes().stream()).toList(),
                observed.formatFailures().stream()
                        .map(
                                f ->
                                        new TurnRecord.FormatFailureSummary(
                                                f.operation().name(), f.reason()))
                        .toList(),
                analysis == null ? null : summarize(analysis),
                delivered == null
                        ? null
                        : new TurnRecord.Response(
                                delivered.text(),
                                delivered.responseType(),
                                delivered.text().strip().length()),
                checks(turn.expect(), analysis, delivered));
    }

    private static TurnRecord.Analysis summarize(AnalysisResult analysis) {
        return new TurnRecord.Analysis(
                analysis.responseAct(),
                analysis.relevance(),
                analysis.learningState(),
                analysis.primaryGap().code(),
                analysis.nextStrategy().type(),
                analysis.nextStrategy().recommendedSupportLevel(),
                analysis.analysisConfidence());
    }

    /** 세트의 기대값과 비교한다. 기대값이 없는 항목은 넣지 않는다. */
    static List<TurnRecord.Check> checks(
            RegressionSet.Expect expect, AnalysisResult analysis, CandidateResponse delivered) {
        List<TurnRecord.Check> checks = new ArrayList<>();
        String act = analysis == null ? null : analysis.responseAct();
        String state = analysis == null ? null : analysis.learningState();
        String strategy = analysis == null ? null : analysis.nextStrategy().type();
        String level = analysis == null ? null : analysis.nextStrategy().recommendedSupportLevel();
        oneOf(checks, "response_act", expect.responseActs(), act, analysis != null);
        oneOf(checks, "learning_state", expect.learningStates(), state, analysis != null);
        if (!expect.notLearningStates().isEmpty()) {
            checks.add(
                    analysis == null
                            ? notEvaluated(
                                    "learning_state_not", "not " + expect.notLearningStates())
                            : new TurnRecord.Check(
                                    "learning_state_not",
                                    expect.notLearningStates().contains(state) ? "FAIL" : "PASS",
                                    "not " + expect.notLearningStates(),
                                    state));
        }
        oneOf(checks, "strategy_type", expect.strategyTypes(), strategy, analysis != null);
        oneOf(checks, "support_level", expect.supportLevels(), level, analysis != null);
        oneOf(
                checks,
                "response_type",
                expect.responseTypes(),
                delivered == null ? null : delivered.responseType(),
                delivered != null);
        if (!expect.forbiddenResponseTerms().isEmpty()) {
            if (delivered == null) {
                checks.add(
                        notEvaluated(
                                "forbidden_terms", "none of " + expect.forbiddenResponseTerms()));
            } else {
                String text = delivered.text().toLowerCase(Locale.ROOT);
                List<String> found =
                        expect.forbiddenResponseTerms().stream()
                                .filter(term -> text.contains(term.toLowerCase(Locale.ROOT)))
                                .toList();
                checks.add(
                        new TurnRecord.Check(
                                "forbidden_terms",
                                found.isEmpty() ? "PASS" : "FAIL",
                                "none of " + expect.forbiddenResponseTerms(),
                                found.isEmpty() ? "none" : found.toString()));
            }
        }
        if (delivered != null) {
            int length = delivered.text().strip().length();
            checks.add(
                    new TurnRecord.Check(
                            "length_le_" + SOFT_RESPONSE_LENGTH,
                            length <= SOFT_RESPONSE_LENGTH ? "PASS" : "FAIL",
                            "<= " + SOFT_RESPONSE_LENGTH,
                            String.valueOf(length)));
        }
        return checks;
    }

    private static void oneOf(
            List<TurnRecord.Check> checks,
            String name,
            List<String> expected,
            String actual,
            boolean available) {
        if (expected.isEmpty()) return;
        if (!available) {
            checks.add(notEvaluated(name, expected.toString()));
            return;
        }
        checks.add(
                new TurnRecord.Check(
                        name,
                        expected.contains(actual) ? "PASS" : "FAIL",
                        expected.toString(),
                        actual));
    }

    private static TurnRecord.Check notEvaluated(String name, String expected) {
        return new TurnRecord.Check(name, "NOT_EVALUATED", expected, null);
    }

    /** 스위트마다 세션 하나. PoC 처럼 play·safety·ladder 를 서로 다른 세션으로 본다. */
    private static UUID sessionId(String suite) {
        return UUID.nameUUIDFromBytes(("eval-session-" + suite).getBytes());
    }
}
