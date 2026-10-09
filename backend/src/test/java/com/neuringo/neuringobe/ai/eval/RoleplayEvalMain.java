package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.config.AiProviderConfiguration;
import com.neuringo.neuringobe.ai.infrastructure.speech.OpenAiSpeechToTextProvider;
import com.neuringo.neuringobe.ai.infrastructure.speech.TypecastTextToSpeechProvider;
import com.neuringo.neuringobe.ai.infrastructure.springai.OpenAiFailureClassifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * 역할극 품질 회귀 측정 실행기. {@code ./gradlew roleplayEval --args="..."} 로 돌린다. 사용법은
 * docs/ai-quality-measurement.md.
 *
 * <pre>
 * --provider scripted|openai   scripted 는 키 없이 실행기만 확인하는 가짜 모델(기본값)
 * --run-id ID                  결과 폴더 이름(기본: 모델-시각)
 * --repeats N                  반복 횟수(기본 1, 모델 비교는 3 이상)
 * --cases P1,L1 / --suites play,ladder   일부만 실행
 * --budget 60s                 턴 예산(운영 상한 60초)
 * --speech                     TTS→STT 음성 왕복도 잰다(TTS_*·STT_* 환경변수 필요)
 * --skip-llm                   음성 왕복만 잰다
 * --note "..."                 summary 에 남길 메모
 * --out build/eval             결과 위치
 * --compare DIR1,DIR2,...      여러 실행의 summary.json 으로 모델 비교표(comparison.md)를 만든다
 * </pre>
 *
 * <p>openai 제공자는 운영과 같은 환경변수를 읽는다: AI_BASE_URL, AI_API_KEY, AI_MODEL, AI_TIMEOUT, AI_PROVIDER_NAME.
 * OpenAI 호환 엔드포인트(Elice MLAPI 등)라면 Claude·Gemini 모델도 같은 방식으로 잰다. 비용은
 * EVAL_PRICE_INPUT_PER_MTOK·EVAL_PRICE_OUTPUT_PER_MTOK (USD / 1M 토큰)를 넣었을 때만 계산한다.
 */
public final class RoleplayEvalMain {

    private RoleplayEvalMain() {}

    public static void main(String[] args) {
        Map<String, String> options = parse(args);
        if (options.containsKey("compare")) {
            compare(options);
            return;
        }
        Path outRoot = Path.of(options.getOrDefault("out", "build/eval"));
        String provider = options.getOrDefault("provider", "scripted");
        RegressionSet set =
                RegressionSet.load(options.getOrDefault("set", RegressionSet.DEFAULT_RESOURCE));
        Predicate<RegressionSet.Case> filter = filter(options);
        int repeats = Integer.parseInt(options.getOrDefault("repeats", "1"));
        Duration budget =
                Duration.parse(
                        "PT" + options.getOrDefault("budget", "60s").toUpperCase(Locale.ROOT));
        String startedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String model =
                "scripted".equals(provider) ? ScriptedLlmProvider.MODEL : requireEnv("AI_MODEL");
        String runId = options.getOrDefault("run-id", defaultRunId(model));
        Path dir = outRoot.resolve(runId);

        if (!options.containsKey("skip-llm")) {
            ConfigurableApplicationContext context = null;
            try {
                LlmProvider llm;
                if ("scripted".equals(provider)) {
                    llm = new ScriptedLlmProvider(Set.<AiOperation>of());
                } else if ("openai".equals(provider)) {
                    context = openAiContext(model);
                    llm = context.getBean(LlmProvider.class);
                } else {
                    throw new IllegalArgumentException("unknown --provider " + provider);
                }
                RoleplayEvalRunner runner =
                        new RoleplayEvalRunner(
                                set,
                                llm,
                                new RoleplayPromptFactory(JsonMapper.builder().build(), null),
                                budget);
                System.out.printf("[%s] %s × %d회 시작%n", runId, model, repeats);
                List<TurnRecord> records =
                        runner.run(
                                repeats,
                                filter,
                                r ->
                                        System.out.printf(
                                                "  %s#%d %-9s %6dms calls=%d eval=%s format=%d%n",
                                                r.caseId(),
                                                r.repeat(),
                                                r.outcome(),
                                                r.totalMs(),
                                                r.llmCalls(),
                                                r.evaluationDecisions(),
                                                r.formatFailures().size()));
                EvalSummary summary =
                        EvalSummary.of(
                                new EvalSummary.Run(
                                        runId,
                                        set.setId(),
                                        provider,
                                        records.stream()
                                                .map(TurnRecord::model)
                                                .filter(m -> m != null)
                                                .findFirst()
                                                .orElse(model),
                                        startedAt,
                                        gitCommit(),
                                        repeats,
                                        budget.toMillis(),
                                        String.join(
                                                ", ",
                                                RoleplayPromptFactory.CAUSE_ANALYSIS_PROMPT_VERSION,
                                                RoleplayPromptFactory
                                                        .RESPONSE_GENERATION_PROMPT_VERSION,
                                                RoleplayPromptFactory
                                                        .RESPONSE_EVALUATION_PROMPT_VERSION),
                                        price("EVAL_PRICE_INPUT_PER_MTOK"),
                                        price("EVAL_PRICE_OUTPUT_PER_MTOK"),
                                        options.get("note")),
                                records);
                EvalReport.writeRun(dir, summary, records, runner.formatFailureSamples());
                System.out.printf(
                        "전달 %s · 첫 PASS %s · 기대값 %s · 형식 실패 %s · p50 %dms p95 %dms%n",
                        summary.delivered().percent(),
                        summary.firstEvaluationPass().percent(),
                        summary.expectation().percent(),
                        summary.formatFailure().percent(),
                        summary.turnLatency().p50(),
                        summary.turnLatency().p95());
            } finally {
                if (context != null) context.close();
            }
        }

        if (options.containsKey("speech")) {
            runSpeech(set, filter, repeats, runId, dir);
        }
        System.out.println("결과: " + dir.toAbsolutePath());
    }

    private static void runSpeech(
            RegressionSet set,
            Predicate<RegressionSet.Case> filter,
            int repeats,
            String runId,
            Path dir) {
        var classifier = new OpenAiFailureClassifier();
        String ttsModel = env("TTS_MODEL", "ssfm-v30");
        String sttModel = env("STT_MODEL", "gpt-4o-mini-transcribe");
        var tts =
                new TypecastTextToSpeechProvider(
                        env("TTS_BASE_URL", "https://api.typecast.ai"),
                        requireEnv("TTS_API_KEY"),
                        Duration.parse("PT" + env("TTS_TIMEOUT", "15s").toUpperCase(Locale.ROOT)),
                        classifier,
                        env("TTS_PROVIDER_NAME", "typecast"),
                        ttsModel,
                        requireEnv("TTS_VOICE_ID"),
                        env("TTS_LANGUAGE", "kor"),
                        AudioFormat.valueOf(
                                env("TTS_AUDIO_FORMAT", "mp3").toUpperCase(Locale.ROOT)));
        var stt =
                new OpenAiSpeechToTextProvider(
                        env("STT_BASE_URL", "https://api.openai.com"),
                        requireEnv("STT_API_KEY"),
                        Duration.parse("PT" + env("STT_TIMEOUT", "15s").toUpperCase(Locale.ROOT)),
                        classifier,
                        env("STT_PROVIDER_NAME", "openai"),
                        sttModel,
                        env("STT_LANGUAGE", "ko"),
                        Boolean.parseBoolean(env("STT_INCLUDE_LOGPROBS", "true")));
        System.out.printf("[%s] 음성 왕복 %s → %s 시작%n", runId, ttsModel, sttModel);
        List<SpeechRoundTrip.Sample> samples =
                new SpeechRoundTrip(set, tts, stt).run(repeats, filter);
        try {
            Files.createDirectories(dir);
            Files.write(
                    dir.resolve("speech.jsonl"),
                    samples.stream().map(EvalReport.JSON::writeValueAsString).toList(),
                    StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("speech-summary.md"),
                    SpeechRoundTrip.markdown(runId, ttsModel, sttModel, samples),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void compare(Map<String, String> options) {
        List<EvalSummary> summaries =
                Arrays.stream(options.get("compare").split(","))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .map(dir -> EvalReport.readSummary(Path.of(dir).resolve("summary.json")))
                        .toList();
        Path out = Path.of(options.getOrDefault("out", "build/eval")).resolve("comparison.md");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, EvalReport.comparison(summaries), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        System.out.println("비교표: " + out.toAbsolutePath());
    }

    /**
     * 운영 설정(AiProviderConfiguration)과 같은 방식으로 Spring AI ChatModel 을 만든다. 명령줄 인자로 넘겨 application.yml
     * 의 기본값(AI_CHAT_PROVIDER=none)보다 우선하게 한다.
     */
    private static ConfigurableApplicationContext openAiContext(String model) {
        String timeout = env("AI_TIMEOUT", "10s");
        List<String> properties =
                new ArrayList<>(
                        List.of(
                                "--spring.ai.model.chat=openai",
                                "--spring.ai.model.embedding=none",
                                "--spring.ai.model.image=none",
                                "--spring.ai.model.audio.speech=none",
                                "--spring.ai.model.audio.transcription=none",
                                "--spring.ai.model.moderation=none",
                                "--spring.ai.openai.base-url="
                                        + env("AI_BASE_URL", "https://api.openai.com"),
                                "--spring.ai.openai.api-key=" + requireEnv("AI_API_KEY"),
                                "--spring.ai.openai.timeout=" + timeout,
                                "--spring.ai.openai.max-retries=0",
                                "--spring.ai.openai.chat.model=" + model,
                                "--neuringo.ai.provider-name="
                                        + env("AI_PROVIDER_NAME", "openai-compatible"),
                                "--neuringo.ai.model=" + model,
                                "--neuringo.ai.request-timeout=" + timeout,
                                "--neuringo.ai.max-retries=0",
                                "--logging.level.root=WARN"));
        return new SpringApplicationBuilder(EvalApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(properties.toArray(String[]::new));
    }

    private static Predicate<RegressionSet.Case> filter(Map<String, String> options) {
        Set<String> cases = csv(options.get("cases"));
        Set<String> suites = csv(options.get("suites"));
        return turn ->
                (cases.isEmpty() || cases.contains(turn.id()))
                        && (suites.isEmpty() || suites.contains(turn.suite()));
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.of(value.split("\\s*,\\s*"));
    }

    /** "--key value", "--key=value", 값 없는 "--flag" 를 받는다. */
    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--"))
                throw new IllegalArgumentException("unexpected argument " + arg);
            String key = arg.substring(2);
            int eq = key.indexOf('=');
            if (eq >= 0) {
                options.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    private static String defaultRunId(String model) {
        String stamp =
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
        return model.replaceAll("[^A-Za-z0-9.-]", "_") + "-" + stamp;
    }

    /** 결과를 낸 코드 버전. 커밋하지 않은 변경이 있으면 "-dirty" 를 붙인다. git 이 없으면 null. */
    private static String gitCommit() {
        try {
            Process process =
                    new ProcessBuilder("git", "describe", "--always", "--dirty")
                            .redirectErrorStream(true)
                            .start();
            String out =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                            .strip();
            return process.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Double price(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Double.valueOf(value);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 필요합니다");
        }
        return value;
    }

    /**
     * TestConfiguration 이라 다른 @SpringBootTest 의 컴포넌트 스캔에서 빠진다. 일반 설정 클래스로 두면 앱 테스트가 이 클래스를 읽어 아래 자동
     * 설정 제외(DB 등)가 섞일 수 있다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                FlywayAutoConfiguration.class
            })
    @Import(AiProviderConfiguration.class)
    static class EvalApplication {}
}
