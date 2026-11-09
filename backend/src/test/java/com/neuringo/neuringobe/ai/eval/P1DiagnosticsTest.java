package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class P1DiagnosticsTest {
    @ParameterizedTest
    @CsvSource({
        "cases,P2",
        "repeats,3",
        "set,other.json",
        "suites,play",
        "speech,true",
        "skip-llm,true",
        "compare,other"
    })
    void rejectsCaptureOutsideTheApprovedSyntheticRun(String key, String value) {
        var options = new HashMap<>(Map.of("diagnostics", "true", "cases", "P1", "repeats", "1"));
        options.put(key, value);
        assertThatThrownBy(() -> P1Diagnostics.requireAllowed(options))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalRunsNeedNoDiagnosticRestrictions() {
        P1Diagnostics.requireAllowed(Map.of("cases", "P2", "repeats", "3"));
        P1Diagnostics.requireAllowed(Map.of("diagnostics", "true", "cases", "P1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {RegressionSet.DEFAULT_RESOURCE, RegressionSet.CHARACTER_P1_RESOURCE})
    void capturesActualPayloadsAndOutputsWithoutAddingCalls(String resource, @TempDir Path dir)
            throws Exception {
        var json = JsonMapper.builder().build();
        var set = RegressionSet.load(resource);
        P1Diagnostics.requireAllowed(Map.of("diagnostics", "true", "cases", "P1", "set", resource));
        var diagnostics = new P1Diagnostics(new ScriptedLlmProvider());
        var runner =
                new RoleplayEvalRunner(
                        set,
                        diagnostics,
                        new RoleplayPromptFactory(json, null),
                        Duration.ofSeconds(60));
        var result = runner.run(1, c -> c.id().equals("P1"), ignored -> {}).getFirst();
        diagnostics.write(dir);
        var lines = Files.readAllLines(dir.resolve("diagnostics.jsonl"));
        assertThat(result.delivered()).isTrue();
        assertThat(result.llmCalls()).isEqualTo(3);
        assertThat(lines).hasSize(3);
        var analysis = json.readTree(lines.getFirst());
        var evaluation = json.readTree(lines.getLast());
        boolean character = set.scenario().aiCharacter() != null;
        assertThat(json.readTree(lines.get(1)).path("prompt_version").asString())
                .isEqualTo(
                        character
                                ? RoleplayPromptFactory.CHARACTER_RESPONSE_GENERATION_PROMPT_VERSION
                                : RoleplayPromptFactory.RESPONSE_GENERATION_PROMPT_VERSION);
        assertThat(evaluation.path("prompt_version").asString())
                .isEqualTo(
                        character
                                ? RoleplayPromptFactory.CHARACTER_RESPONSE_EVALUATION_PROMPT_VERSION
                                : RoleplayPromptFactory.RESPONSE_EVALUATION_PROMPT_VERSION);
        assertThat(evaluation.path("role_type").asString())
                .isEqualTo(set.scenario().aiCharacter() == null ? "NARRATOR" : "CHARACTER");
        assertThat(evaluation.path("native_schema").asBoolean()).isTrue();
        assertThat(evaluation.path("analysis_id")).isEqualTo(analysis.path("analysis_id"));
        assertThat(
                        evaluation
                                .path("input")
                                .path("learner_turn")
                                .path("canonical_utterance")
                                .asString())
                .isEqualTo("너 발표하다가 말을 못 찾아서 중간에 멈추고 그냥 앉았지?");
        for (String field :
                java.util.List.of(
                        "observation_tasks",
                        "current_goal_assessment",
                        "activity_assessment",
                        "interpretation_strategy")) {
            assertThat(evaluation.path("input").path(field).isObject()).isTrue();
        }
        assertThat(evaluation.path("input").has("analysis_result")).isFalse();
        assertThat(evaluation.path("input").path("candidate_response").isObject()).isTrue();
        assertThat(json.readTree(evaluation.path("output").asString()).path("decision").asString())
                .isEqualTo("PASS");
        assertThat(lines.toString()).doesNotContain("Authorization", "AI_API_KEY", "apiKey");
    }
}
