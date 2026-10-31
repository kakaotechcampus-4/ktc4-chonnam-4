package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.PreparedPrompt;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 시나리오 생성 품질 평가 실행기. 세트의 강사 입력마다 최대 3번 생성한다. 출력이 계약 검사에서 실패하면 실패 코드를 붙여 다시 만든다(워크플로우 v3 7.3 의 "동일
 * 실패 반복 시 실패 코드 전달").
 *
 * <p>실제 호출 순서와 한도는 운영 오케스트레이터(진미나 담당)가 정한다. 여기서는 그 정책의 기본안(형식 실패 후 같은 입력 + 실패 코드로 재생성, 최대 3회)으로 잰다.
 */
final class ScenarioEvalRunner {

    static final int MAX_ATTEMPTS = 3;

    private final ScenarioRegressionSet set;
    private final RecordingLlmProvider llm;
    private final ScenarioPromptFactory factory;

    ScenarioEvalRunner(ScenarioRegressionSet set, LlmProvider llm, ScenarioPromptFactory factory) {
        this.set = Objects.requireNonNull(set);
        this.llm = new RecordingLlmProvider(llm);
        this.factory = Objects.requireNonNull(factory);
    }

    List<ScenarioRecord> run(
            int repeats,
            Predicate<ScenarioRegressionSet.Case> filter,
            Consumer<ScenarioRecord> progress) {
        List<ScenarioRecord> records = new ArrayList<>();
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (ScenarioRegressionSet.Case testCase : set.cases()) {
                if (!filter.test(testCase)) continue;
                ScenarioRecord record = runCase(testCase, repeat);
                records.add(record);
                progress.accept(record);
            }
        }
        return records;
    }

    private ScenarioRecord runCase(ScenarioRegressionSet.Case testCase, int repeat) {
        AiTraceContext trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        1,
                        null,
                        null,
                        null,
                        null);
        int from = llm.size();
        List<ScenarioRecord.FormatFailure> failures = new ArrayList<>();
        String providerFailure = null;
        GeneratedScenario scenario = null;
        long startedAt = System.nanoTime();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS && scenario == null; attempt++) {
            ScenarioGenerationInput input = testCase.input(failureCodes(failures));
            PreparedPrompt<GeneratedScenario> prompt =
                    factory.generation(trace, new AiAttemptContext(0, attempt, 0), attempt, input);
            AiCallResult<LlmCompletion> result = llm.complete(prompt.request());
            if (result instanceof AiCallResult.Failure<LlmCompletion> failure) {
                providerFailure = failure.failure().type().name();
                break;
            }
            String content = ((AiCallResult.Success<LlmCompletion>) result).data().content();
            try {
                scenario = prompt.parser().parse(content);
            } catch (InvalidLlmOutputException exception) {
                failures.add(new ScenarioRecord.FormatFailure(attempt, reason(exception), content));
            }
        }

        long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
        List<RecordingLlmProvider.Call> calls = llm.since(from);
        return new ScenarioRecord(
                testCase.id(),
                testCase.difficulty().scenarioLevel(),
                repeat,
                calls.stream()
                        .map(RecordingLlmProvider.Call::model)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null),
                scenario != null,
                scenario != null && failures.isEmpty(),
                calls.size(),
                totalMs,
                calls.stream().mapToLong(RecordingLlmProvider.Call::wallMs).boxed().toList(),
                calls.stream().mapToInt(c -> c.inputTokens() == null ? 0 : c.inputTokens()).sum(),
                calls.stream().mapToInt(c -> c.outputTokens() == null ? 0 : c.outputTokens()).sum(),
                providerFailure,
                List.copyOf(failures),
                scenario,
                scenario == null ? List.of() : testCase.expectation().check(scenario));
    }

    /** "CODE: field" 에서 코드만. Jackson 구조 오류는 원인 예외 이름을 붙인다. */
    private static String reason(InvalidLlmOutputException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (exception.getCause() != null) {
            return message + " (" + exception.getCause().getClass().getSimpleName() + ")";
        }
        return message;
    }

    private static List<String> failureCodes(List<ScenarioRecord.FormatFailure> failures) {
        return failures.stream()
                .map(failure -> failure.reason().split("[: ]", 2)[0])
                .map(code -> code.startsWith("LLM") ? "INVALID_STRUCTURE" : code)
                .map(code -> code.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
