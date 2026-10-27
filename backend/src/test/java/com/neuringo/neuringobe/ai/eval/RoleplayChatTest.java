package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RoleplayChatTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void generatesScenarioThenRunsTurnsThroughTheRealPipeline() {
        RoleplayChat chat = chat();

        RoleplayChat.ScenarioResult result = chat.generateScenario(teacher(), "L1");
        assertThat(result.scenario()).isNotNull();
        assertThat(result.calls()).isEqualTo(1);

        String opening = chat.open("S1");
        assertThat(opening).isEqualTo(result.scenario().startingQuestions().get("S1"));

        RoleplayChat.TurnOutcome outcome = chat.turn("친구가 넘어졌어");
        assertThat(outcome.reply()).isNotBlank();
        assertThat(outcome.calls()).isEqualTo(3);
        assertThat(outcome.judgement().goal()).isEqualTo("MG-01");
        assertThat(chat.turnCount()).isEqualTo(1);
        assertThat(chat.goalStatusLine()).startsWith("MG-01 ");
    }

    @Test
    void openRejectsSupportLevelWithoutStartingQuestion() {
        RoleplayChat chat = chat();
        chat.generateScenario(teacher(), "L1");

        assertThatThrownBy(() -> chat.open("S3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionWritesTranscriptFromConsoleInput(@TempDir Path dir) throws Exception {
        String input =
                String.join(
                        "\n",
                        "", // 학습 목표 기본값
                        "",
                        "",
                        "",
                        "",
                        "",
                        "L1",
                        "", // 이 시나리오로
                        "S1",
                        "/상태",
                        "친구가 넘어졌어",
                        "/끝",
                        "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RoleplayChatMain.Console console =
                new RoleplayChatMain.Console(
                        new BufferedReader(new StringReader(input)),
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));

        new RoleplayChatMain.Session(chat(), console, ScriptedLlmProvider.MODEL, Map.of(), dir)
                .run();

        String out = bytes.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("역할극 시작 (지원 S1)").contains("└ 판단: MG-01").contains("대화 기록:");
        try (var files = Files.list(dir)) {
            Path transcript = files.findFirst().orElseThrow();
            assertThat(Files.readString(transcript))
                    .contains("## 대화")
                    .contains("**아동:** 친구가 넘어졌어")
                    .contains("LLM 호출 4번");
        }
    }

    private static RoleplayChat chat() {
        return new RoleplayChat(
                new ScriptedLlmProvider(),
                new ScenarioPromptFactory(JSON, null),
                new RoleplayPromptFactory(JSON, null),
                Duration.ofSeconds(60),
                new RoleplayChat.Policy(8, 3));
    }

    private static ScenarioGenerationInput.TeacherInput teacher() {
        return new ScenarioGenerationInput.TeacherInput(
                "친구의 감정을 이해하고 공감하기",
                null,
                "놀이터",
                List.of(Map.of("role", "같은 반 친구")),
                List.of(),
                List.of());
    }
}
