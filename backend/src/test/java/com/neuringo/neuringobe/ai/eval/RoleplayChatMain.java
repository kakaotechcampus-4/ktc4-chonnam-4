package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시나리오를 만들고 아동 역할로 AI 와 직접 대화해 보는 도구. {@code ./gradlew -q --console=plain roleplayChat} 로 돌린다. 사용법은
 * docs/roleplay-chat.md.
 *
 * <pre>
 * --provider openai|scripted   scripted 는 키 없이 흐름만 보는 가짜 모델(기본 openai)
 * --case L1_PLAYGROUND         강사 입력을 묻지 않고 시나리오 평가 세트의 입력을 쓴다
 * --level L1|L2|L3             난이도(기본: 물어본다)
 * --support S0|S1|S2           아동의 초기 지원 수준(기본: 물어본다)
 * --max-turns N                턴 상한(기본 8)
 * --max-support-turns N        목표 하나당 지원 턴 상한(기본 3)
 * --budget 60s                 한 턴 예산(기본 60s)
 * --judgement off              모델의 판단(학습 상태·전략·목표 상태)을 숨긴다
 * --out build/eval/chat        대화 기록 위치
 * </pre>
 *
 * <p>대화 중 명령: /상태, /시나리오, /판단(보이기·숨기기), /끝.
 */
public final class RoleplayChatMain {

    private static final String DEFAULT_GOAL = "친구의 감정을 이해하고 공감하기";

    private RoleplayChatMain() {}

    public static void main(String[] args) {
        Map<String, String> options = RoleplayEvalMain.parse(args);
        String provider = options.getOrDefault("provider", "openai");
        Charset inputCharset = Charset.forName(System.getProperty("chat.input.encoding", "UTF-8"));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, inputCharset));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        String model =
                "scripted".equals(provider)
                        ? ScriptedLlmProvider.MODEL
                        : RoleplayEvalMain.requireEnv("AI_MODEL");
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
            JsonMapper json = JsonMapper.builder().build();
            RoleplayChat chat =
                    new RoleplayChat(
                            llm,
                            new ScenarioPromptFactory(json, null),
                            new RoleplayPromptFactory(json, null),
                            Duration.parse(
                                    "PT"
                                            + options.getOrDefault("budget", "60s")
                                                    .toUpperCase(Locale.ROOT)),
                            new RoleplayChat.Policy(
                                    Integer.parseInt(options.getOrDefault("max-turns", "8")),
                                    Integer.parseInt(
                                            options.getOrDefault("max-support-turns", "3"))));
            new Session(
                            chat,
                            new Console(in, out),
                            model,
                            options,
                            Path.of(options.getOrDefault("out", "build/eval/chat")))
                    .run();
        } finally {
            if (context != null) context.close();
        }
    }

    /** 표준 입출력. 깨진 글자(U+FFFD)가 들어오면 콘솔 인코딩을 안내한다. */
    record Console(BufferedReader in, PrintStream out) {

        String ask(String prompt, String fallback) {
            out.print(fallback == null ? prompt + ": " : prompt + " [" + fallback + "]: ");
            out.flush();
            String line = readLine();
            if (line == null) return null;
            line = line.strip();
            return line.isEmpty() ? fallback : line;
        }

        String readLine() {
            try {
                String line = in.readLine();
                if (line != null && line.indexOf('�') >= 0) {
                    out.println("  ! 한글이 깨져서 들어왔습니다. Windows 라면 실행 전에 chcp 65001 을 입력하세요.");
                }
                return line;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    static final class Session {

        private final RoleplayChat chat;
        private final Console console;
        private final String model;
        private final Map<String, String> options;
        private final Path outDir;
        private final StringBuilder transcript = new StringBuilder();
        private final Path transcriptFile;
        private boolean showJudgement;

        Session(
                RoleplayChat chat,
                Console console,
                String model,
                Map<String, String> options,
                Path outDir) {
            this.chat = chat;
            this.console = console;
            this.model = model;
            this.options = options;
            this.outDir = outDir;
            this.showJudgement = !"off".equals(options.get("judgement"));
            this.transcriptFile =
                    outDir.resolve(
                            "chat-"
                                    + LocalDateTime.now()
                                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                                    + ".md");
        }

        void run() {
            PrintStream out = console.out();
            out.println("== 느링고 역할극 대화 · " + model + " ==");
            out.println("시나리오를 만든 뒤, 아동이 되어 AI 와 대화합니다. 대화 중 /끝 으로 마칩니다.");
            out.println();

            GeneratedScenario scenario = null;
            while (scenario == null) {
                Teacher teacher = teacherInput();
                if (teacher == null) return;
                out.println();
                out.println("시나리오를 만드는 중...");
                RoleplayChat.ScenarioResult result =
                        chat.generateScenario(teacher.input(), teacher.level());
                if (result.scenario() == null) {
                    out.printf(
                            "시나리오를 만들지 못했습니다(%s). 다시 시도합니다.%n%n",
                            result.providerFailure() != null
                                    ? "제공자 실패 " + result.providerFailure()
                                    : "계약 검사 실패 " + result.formatFailures());
                    if (options.containsKey("case")) return;
                    continue;
                }
                out.printf(
                        "만들었습니다 (%d번 호출, %.1fs%s)%n%n",
                        result.calls(),
                        result.totalMs() / 1000.0,
                        result.formatFailures().isEmpty()
                                ? ""
                                : ", 검사 실패 후 다시 만듦 " + result.formatFailures());
                printScenario(result.scenario());
                String answer = console.ask("이 시나리오로 대화할까요? (Enter=예, r=다시 만들기, q=끝)", "y");
                if (answer == null || answer.equalsIgnoreCase("q")) return;
                if (answer.equalsIgnoreCase("r")) {
                    out.println();
                    continue;
                }
                scenario = result.scenario();
                recordScenario(teacher, scenario);
            }

            String support = options.get("support");
            while (support == null || !scenario.startingQuestions().containsKey(support)) {
                support = console.ask("아동의 초기 지원 수준 (S0 열린 질문 · S1 단서 · S2 선택지)", "S1");
                if (support == null) return;
                support = support.toUpperCase(Locale.ROOT);
            }
            String opening = chat.open(support);
            out.println();
            out.println("---- 역할극 시작 (지원 " + support + ") ----");
            say("AI", opening);
            save();

            while (true) {
                out.print("나(아동) > ");
                out.flush();
                String line = console.readLine();
                if (line == null) break;
                line = line.strip();
                if (line.isEmpty()) continue;
                if (command(line)) {
                    if (line.equals("/끝") || line.equalsIgnoreCase("/quit")) break;
                    continue;
                }
                RoleplayChat.TurnOutcome outcome = chat.turn(line);
                if (outcome.reply() == null) {
                    out.printf(
                            "  ! 이번 턴은 전달되지 못했습니다: %s (%d번 호출, %.1fs). 다시 말해 보세요.%n",
                            outcome.failure(), outcome.calls(), outcome.totalMs() / 1000.0);
                    transcript.append("- (전달 실패: ").append(outcome.failure()).append(")\n");
                    save();
                    continue;
                }
                transcript.append("- **아동:** ").append(line).append('\n');
                say("AI", outcome.reply());
                RoleplayChat.Judgement j = outcome.judgement();
                String judgement =
                        "%s · 학습 상태 %s · 빈 곳 %s · 전략 %s → %s(%s, %s)"
                                .formatted(
                                        j.goal(),
                                        j.learningState(),
                                        j.gap(),
                                        j.strategy(),
                                        j.responseType(),
                                        j.supportLevel(),
                                        j.targetGoal());
                String status =
                        "%s · %d번 호출 %.1fs"
                                .formatted(
                                        j.goalStatuses(),
                                        outcome.calls(),
                                        outcome.totalMs() / 1000.0);
                if (showJudgement) {
                    out.println("    └ 판단: " + judgement);
                    out.println("    └ 목표: " + status);
                }
                transcript.append("  - 판단: ").append(judgement).append('\n');
                transcript.append("  - 목표: ").append(status).append('\n');
                save();
                if (outcome.finished()) {
                    out.println("---- 역할극 끝 (" + chat.turnCount() + "턴) ----");
                    break;
                }
            }
            summary();
        }

        private record Teacher(ScenarioGenerationInput.TeacherInput input, String level) {}

        private Teacher teacherInput() {
            String caseId = options.get("case");
            if (caseId != null) {
                ScenarioRegressionSet.Case preset =
                        ScenarioRegressionSet.load(ScenarioRegressionSet.DEFAULT_RESOURCE)
                                .cases()
                                .stream()
                                .filter(c -> c.id().equals(caseId))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "unknown --case " + caseId));
                return new Teacher(
                        preset.teacherInput(),
                        options.getOrDefault("level", preset.difficulty().scenarioLevel()));
            }
            console.out().println("강사 입력 (Enter 는 [ ] 안의 기본값, 여러 개는 쉼표로 구분)");
            String goal = console.ask("학습 목표", DEFAULT_GOAL);
            if (goal == null) return null;
            String category = console.ask("분류 (예: 감정 인식, 공감 표현, 상황 대처)", "");
            String situation = console.ask("상황", "놀이터");
            String character = console.ask("등장인물", "같은 반 친구");
            String required = console.ask("꼭 넣을 요소", "");
            String forbidden = console.ask("쓰면 안 되는 표현", "");
            String level = options.get("level");
            while (level == null || !Set.of("L1", "L2", "L3").contains(level)) {
                level = console.ask("난이도 (L1 쉬움 · L2 이유 추론 · L3 섞인 감정·해결)", "L1");
                if (level == null) return null;
                level = level.toUpperCase(Locale.ROOT);
            }
            List<Map<String, Object>> characters =
                    blank(character) ? List.of() : List.of(Map.of("role", character));
            return new Teacher(
                    new ScenarioGenerationInput.TeacherInput(
                            goal,
                            blank(category) ? null : category,
                            blank(situation) ? null : situation,
                            characters,
                            list(required),
                            list(forbidden)),
                    level);
        }

        private boolean command(String line) {
            PrintStream out = console.out();
            switch (line) {
                case "/상태", "/state" ->
                        out.printf(
                                "  턴 %d · 지원 %s · 현재 목표 %s · %s%n",
                                chat.turnCount(),
                                chat.supportLevel(),
                                chat.currentGoalKey(),
                                chat.goalStatusLine());
                case "/시나리오", "/scenario" -> printScenario(chat.scenario());
                case "/판단", "/judgement" -> {
                    showJudgement = !showJudgement;
                    out.println("  판단 " + (showJudgement ? "보이기" : "숨기기"));
                }
                case "/끝", "/quit" -> {}
                default -> {
                    if (!line.startsWith("/")) return false;
                    out.println("  명령: /상태 /시나리오 /판단 /끝");
                }
            }
            return true;
        }

        private void printScenario(GeneratedScenario s) {
            PrintStream out = console.out();
            out.println("[" + s.scenarioLevel() + "] " + s.title());
            out.println("  요약: " + s.summary());
            out.println("  사실:");
            s.scenarioFacts().forEach(f -> out.println("    - " + f));
            out.println("  금지 추론:");
            s.prohibitedInferences().forEach(f -> out.println("    - " + f));
            out.println("  목표:");
            s.microGoals()
                    .forEach(
                            g -> {
                                out.println("    " + g.id() + " " + g.description());
                                out.println(
                                        "        증거: " + String.join(" / ", g.requiredEvidence()));
                                out.println("        예시: " + g.achievementExample());
                            });
            out.println("  시작 질문:");
            s.startingQuestions().forEach((k, v) -> out.println("    " + k + " " + v));
            out.println();
        }

        private void recordScenario(Teacher teacher, GeneratedScenario s) {
            transcript.append("# 역할극 대화 기록\n\n");
            transcript.append("- 모델: ").append(model).append('\n');
            transcript.append("- 학습 목표: ").append(teacher.input().learningGoal()).append('\n');
            transcript.append("- 난이도: ").append(s.scenarioLevel()).append("\n\n");
            transcript.append("## 시나리오: ").append(s.title()).append("\n\n");
            transcript.append(s.summary()).append("\n\n");
            transcript.append("사실\n\n");
            s.scenarioFacts().forEach(f -> transcript.append("- ").append(f).append('\n'));
            transcript.append("\n금지 추론\n\n");
            s.prohibitedInferences().forEach(f -> transcript.append("- ").append(f).append('\n'));
            transcript.append("\n목표\n\n");
            s.microGoals()
                    .forEach(
                            g ->
                                    transcript
                                            .append("- ")
                                            .append(g.id())
                                            .append(' ')
                                            .append(g.description())
                                            .append(" (증거: ")
                                            .append(String.join(" / ", g.requiredEvidence()))
                                            .append(")\n"));
            transcript.append("\n## 대화\n\n");
        }

        private void say(String speaker, String text) {
            console.out().println(speaker + " > " + text);
            transcript.append("- **").append(speaker).append(":** ").append(text).append('\n');
        }

        private void summary() {
            PrintStream out = console.out();
            List<RecordingLlmProvider.Call> calls = chat.calls();
            int input =
                    calls.stream()
                            .mapToInt(c -> c.inputTokens() == null ? 0 : c.inputTokens())
                            .sum();
            int output =
                    calls.stream()
                            .mapToInt(c -> c.outputTokens() == null ? 0 : c.outputTokens())
                            .sum();
            Double inPrice = RoleplayEvalMain.price("EVAL_PRICE_INPUT_PER_MTOK");
            Double outPrice = RoleplayEvalMain.price("EVAL_PRICE_OUTPUT_PER_MTOK");
            String cost =
                    inPrice == null || outPrice == null
                            ? ""
                            : " · 약 $%.4f"
                                    .formatted((input * inPrice + output * outPrice) / 1_000_000);
            String line = "LLM 호출 %d번 · 토큰 %d / %d%s".formatted(calls.size(), input, output, cost);
            out.println(line);
            transcript
                    .append("\n---\n\n")
                    .append(chat.goalStatusLine())
                    .append("  \n")
                    .append(line)
                    .append('\n');
            save();
            out.println("대화 기록: " + transcriptFile.toAbsolutePath());
        }

        private void save() {
            if (transcript.isEmpty()) return;
            try {
                Files.createDirectories(outDir);
                Files.writeString(transcriptFile, transcript, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }

        private static List<String> list(String value) {
            if (blank(value)) return List.of();
            return Arrays.stream(value.split(","))
                    .map(String::strip)
                    .filter(v -> !v.isEmpty())
                    .toList();
        }
    }
}
