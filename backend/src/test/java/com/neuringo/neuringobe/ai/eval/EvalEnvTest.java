package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalEnvTest {
    @TempDir Path directory;

    @Test
    void localFileOverridesInheritedEnvironmentAndReloadsChanges() throws IOException {
        Path file = directory.resolve(".env.local");
        Files.writeString(file, "PATH=local-setting\nAI_MODEL='candidate-one'\n");
        assertThat(EvalEnv.load(file).require("PATH")).isEqualTo("local-setting");
        assertThat(EvalEnv.load(file).require("AI_MODEL")).isEqualTo("candidate-one");
        Files.writeString(file, "export AI_MODEL=\"candidate-two\"\n");
        assertThat(EvalEnv.load(file).require("AI_MODEL")).isEqualTo("candidate-two");
    }

    @Test
    void supportsUtf16EnvFiles() throws IOException {
        Path file = directory.resolve(".env.local");
        Files.writeString(file, "AI_MODEL=candidate\n", StandardCharsets.UTF_16);
        assertThat(EvalEnv.load(file).require("AI_MODEL")).isEqualTo("candidate");
    }

    @Test
    void diagnosticRerunsKeepSeparateResultsForTheSelectedModel() throws IOException {
        Path first = RoleplayEvalMain.diagnosticOutput("config-test", "provider/model");
        Path second = RoleplayEvalMain.diagnosticOutput("config-test", "provider/model");
        try {
            Files.writeString(first.resolve("result.json"), "first result");
            assertThat(first).isNotEqualTo(second);
            assertThat(first.getFileName().toString()).startsWith("config-test-provider_model-");
            assertThat(Files.readString(first.resolve("result.json"))).isEqualTo("first result");
            assertThat(Files.exists(second.resolve("result.json"))).isFalse();
        } finally {
            Files.deleteIfExists(first.resolve("result.json"));
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    @Test
    void fallsBackToShellAndReportsMissingSettingsWithoutValues() {
        EvalEnv env = EvalEnv.load(directory.resolve("missing"));
        assertThat(env.get("PATH", "fallback")).isEqualTo(System.getenv("PATH"));
        assertThat(env.get("NEURINGO_TEST_ABSENT_SETTING", "fallback")).isEqualTo("fallback");
        assertThatThrownBy(() -> env.require("NEURINGO_TEST_ABSENT_SETTING"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEURINGO_TEST_ABSENT_SETTING is not set");
    }
}
