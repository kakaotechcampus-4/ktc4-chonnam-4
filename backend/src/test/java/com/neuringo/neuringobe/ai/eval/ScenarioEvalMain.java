package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시나리오 생성 품질 평가 실행기. {@code ./gradlew scenarioEval --args="..."} 로 돌린다. 사용법은
 * docs/scenario-generation.md.
 *
 * <pre>
 * --provider scripted|openai   scripted 는 키 없이 실행기만 확인하는 가짜 모델(기본값)
 * --run-id ID                  결과 폴더 이름(기본: scenario-모델-시각)
 * --repeats N                  반복 횟수(기본 1)
 * --cases L1_PLAYGROUND,...    일부만 실행
 * --note "..."                 summary 에 남길 메모
 * --out build/eval             결과 위치
 * </pre>
 *
 * <p>openai 제공자는 roleplayEval 과 같은 환경변수(AI_BASE_URL, AI_API_KEY, AI_MODEL, AI_TIMEOUT)와
 * backend/.env.local 을 쓴다. 시나리오 생성은 턴 예산이 없는 호출이라 AI_TIMEOUT 이 그대로 제한 시간이다. 생성 결과가 길어서 30s 이상을 권한다.
 */
public final class ScenarioEvalMain {

    private ScenarioEvalMain() {}

    public static void main(String[] args) {
        Map<String, String> options = RoleplayEvalMain.parse(args);
        String provider = options.getOrDefault("provider", "scripted");
        int repeats = Integer.parseInt(options.getOrDefault("repeats", "1"));
        ScenarioRegressionSet set =
                ScenarioRegressionSet.load(ScenarioRegressionSet.DEFAULT_RESOURCE);
        Set<String> cases = RoleplayEvalMain.csv(options.get("cases"));
        Predicate<ScenarioRegressionSet.Case> filter =
                testCase -> cases.isEmpty() || cases.contains(testCase.id());
        String model =
                "scripted".equals(provider)
                        ? ScriptedLlmProvider.MODEL
                        : RoleplayEvalMain.requireEnv("AI_MODEL");
        String runId =
                options.getOrDefault("run-id", "scenario-" + RoleplayEvalMain.defaultRunId(model));
        Path dir = Path.of(options.getOrDefault("out", "build/eval")).resolve(runId);
        String startedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        ConfigurableApplicationContext context = null;
        try {
            LlmProvider llm;
            if ("scripted".equals(provider)) {
                llm = new ScriptedLlmProvider(Set.<AiOperation>of());
            } else if ("openai".equals(provider)) {
                context = RoleplayEvalMain.openAiContext(model);
                llm = context.getBean(LlmProvider.class);
            } else {
                throw new IllegalArgumentException("unknown --provider " + provider);
            }
            ScenarioEvalRunner runner =
                    new ScenarioEvalRunner(
                            set,
                            llm,
                            new ScenarioPromptFactory(JsonMapper.builder().build(), null));
            System.out.printf("[%s] scenario-generation/v1 · %s × %d회 시작%n", runId, model, repeats);
            List<ScenarioRecord> records =
                    runner.run(
                            repeats,
                            filter,
                            r ->
                                    System.out.printf(
                                            "  %s#%d %-4s %s calls=%d %dms%s%n",
                                            r.caseId(),
                                            r.repeat(),
                                            r.scenarioLevel(),
                                            r.delivered() ? "PASS" : "FAIL",
                                            r.calls(),
                                            r.totalMs(),
                                            r.formatFailures().isEmpty()
                                                    ? ""
                                                    : " "
                                                            + r.formatFailures().stream()
                                                                    .map(
                                                                            ScenarioRecord
                                                                                            .FormatFailure
                                                                                    ::reason)
                                                                    .toList()));
            String servedModel =
                    records.stream()
                            .map(ScenarioRecord::model)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(model);
            ScenarioEvalReport.write(
                    dir,
                    new ScenarioEvalReport.Run(
                            runId,
                            set.setId(),
                            provider,
                            servedModel,
                            startedAt,
                            RoleplayEvalMain.gitCommit(),
                            repeats,
                            RoleplayEvalMain.price("EVAL_PRICE_INPUT_PER_MTOK"),
                            RoleplayEvalMain.price("EVAL_PRICE_OUTPUT_PER_MTOK"),
                            options.get("note")),
                    records);
            System.out.println(ScenarioEvalReport.oneLine(records));
            System.out.println("result: " + dir.toAbsolutePath());
        } finally {
            if (context != null) context.close();
        }
    }
}
