package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ScenarioEvalRunnerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ScenarioRegressionSet set =
            ScenarioRegressionSet.load(ScenarioRegressionSet.DEFAULT_RESOURCE);

    @Test
    void setCoversAllLevelsAndCategories() {
        assertThat(set.cases()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(set.cases())
                .extracting(c -> c.difficulty().scenarioLevel())
                .contains("L1", "L2", "L3");
        assertThat(set.cases())
                .extracting(c -> c.teacherInput().category())
                .contains("EMOTION_RECOGNITION", "EMPATHY_EXPRESSION", "SITUATION_COPING");
    }

    @Test
    void scriptedModelPassesContractForEveryCase() {
        List<ScenarioRecord> records = runner(Set.of()).run(1, c -> true, r -> {});

        assertThat(records).hasSize(set.cases().size());
        assertThat(records).allMatch(ScenarioRecord::firstAttemptPass);
        assertThat(records).allMatch(r -> r.calls() == 1);
    }

    @Test
    void formatFailureIsRetriedWithFailureCodes() {
        List<String> userPrompts = new ArrayList<>();
        ScriptedLlmProvider scripted =
                new ScriptedLlmProvider(Set.of(AiOperation.SCENARIO_GENERATION));
        ScenarioEvalRunner runner =
                new ScenarioEvalRunner(
                        set,
                        request -> {
                            userPrompts.add(request.userPrompt());
                            return scripted.complete(request);
                        },
                        new ScenarioPromptFactory(JSON, null));

        List<ScenarioRecord> records = runner.run(1, c -> c.id().equals("L1_PLAYGROUND"), r -> {});

        ScenarioRecord record = records.getFirst();
        assertThat(record.delivered()).isTrue();
        assertThat(record.firstAttemptPass()).isFalse();
        assertThat(record.calls()).isEqualTo(2);
        assertThat(record.formatFailures()).hasSize(1);
        JsonNode retry = JSON.readTree(userPrompts.get(1)).path("revision");
        assertThat(retry.path("failure_codes").get(0).asString()).isEqualTo("INVALID_STRUCTURE");
        assertThat(retry.has("previous_scenario")).isFalse();
    }

    @Test
    void writesSummaryScenariosAndFailures(@TempDir Path dir) throws Exception {
        List<ScenarioRecord> records =
                runner(Set.of(AiOperation.SCENARIO_GENERATION)).run(1, c -> true, r -> {});

        ScenarioEvalReport.write(
                dir,
                new ScenarioEvalReport.Run(
                        "test",
                        set.setId(),
                        "scripted",
                        ScriptedLlmProvider.MODEL,
                        "now",
                        "abc",
                        1,
                        0.2,
                        1.25,
                        null),
                records);

        String summary = Files.readString(dir.resolve("summary.md"));
        assertThat(summary).contains("최종 통과율").contains("첫 시도 통과율").contains("L3_COPING");
        assertThat(Files.readString(dir.resolve("scenarios.md")))
                .contains("시작 질문")
                .contains("MG-01");
        assertThat(Files.readAllLines(dir.resolve("format-failures.jsonl")))
                .hasSize(set.cases().size());
        assertThat(Files.readAllLines(dir.resolve("results.jsonl"))).hasSize(set.cases().size());
    }

    private ScenarioEvalRunner runner(Set<AiOperation> formatFailureOnce) {
        return new ScenarioEvalRunner(
                set,
                new ScriptedLlmProvider(formatFailureOnce),
                new ScenarioPromptFactory(JSON, null));
    }
}
