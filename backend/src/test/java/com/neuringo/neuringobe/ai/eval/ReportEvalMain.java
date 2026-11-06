package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.eval.ReportEvalReport.Price;
import com.neuringo.neuringobe.report.application.RecommendationComposer;
import com.neuringo.neuringobe.report.application.port.SimilarGoal;
import com.neuringo.neuringobe.report.application.similarity.InMemoryGoalSimilaritySearch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 학습 리포트 루프 실측 실행기. {@code ./gradlew reportEval --args="..."} 로 돌린다.
 *
 * <pre>
 * --provider scripted|openai   scripted 는 키 없이 실행기만 확인하는 가짜 모델(기본값)
 * --gen haiku55 --review sonnet55
 *                              초안·평가 모델. .env.local 의 MODEL_{이름}_URL·_ID·_PRICE 를 읽는다.
 *                              없으면 AI_BASE_URL·AI_MODEL. --review 가 없으면 초안 모델이 평가도 한다
 * --embeddings                 EMBEDDING_BASE_URL·EMBEDDING_MODEL 로 목표 유사도를 잰다
 * --cases R1,R3  --repeats 3  --run-id 이름  --out build/eval-report
 * </pre>
 *
 * <p>설정값은 셸 환경변수, 없으면 backend/.env.local 에서 읽는다(EvalEnv). 키는 출력하지 않는다. 결과는 out/run-id/report.md 에
 * 쓴다.
 */
public final class ReportEvalMain {

    private ReportEvalMain() {}

    public static void main(String[] args) {
        Map<String, String> options = RoleplayEvalMain.parse(args);
        EvalEnv env = EvalEnv.load(Path.of(options.getOrDefault("env", ".env.local")));
        String provider = options.getOrDefault("provider", "scripted");
        ReportRegressionSet set =
                ReportRegressionSet.load(
                        options.getOrDefault("set", ReportRegressionSet.DEFAULT_RESOURCE));
        Set<String> cases = csv(options.get("cases"));
        int repeats = Integer.parseInt(options.getOrDefault("repeats", "1"));
        String startedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        List<ConfigurableApplicationContext> contexts = new ArrayList<>();
        try {
            LlmProvider llm;
            String generationModel;
            String reviewModel;
            Map<AiOperation, Price> prices = new EnumMap<>(AiOperation.class);
            if ("scripted".equals(provider)) {
                llm = new ScriptedReportLlm();
                generationModel = ScriptedReportLlm.MODEL;
                reviewModel = ScriptedReportLlm.MODEL;
                prices.put(AiOperation.REPORT_GENERATION, new Price(0, 0));
                prices.put(AiOperation.REPORT_REVIEW, new Price(0, 0));
            } else if ("openai".equals(provider)) {
                String apiKey = env.require("AI_API_KEY");
                String timeout = env.get("AI_TIMEOUT", "60s");
                ModelSpec generationSpec = ModelSpec.of(env, options.get("gen"));
                ModelSpec reviewSpec =
                        ModelSpec.of(env, options.getOrDefault("review", options.get("gen")));
                generationModel = generationSpec.modelId();
                reviewModel = reviewSpec.modelId();
                ConfigurableApplicationContext generation =
                        context(generationSpec, apiKey, timeout);
                contexts.add(generation);
                LlmProvider generationProvider = generation.getBean(LlmProvider.class);
                if (reviewSpec.equals(generationSpec)) {
                    llm = generationProvider;
                } else {
                    ConfigurableApplicationContext review = context(reviewSpec, apiKey, timeout);
                    contexts.add(review);
                    llm =
                            new RoutingLlmProvider(
                                    generationProvider, review.getBean(LlmProvider.class));
                }
                prices.put(AiOperation.REPORT_GENERATION, generationSpec.price());
                prices.put(AiOperation.REPORT_REVIEW, reviewSpec.price());
            } else {
                throw new IllegalArgumentException("unknown --provider " + provider);
            }
            prices.values().removeIf(p -> p == null);

            String runId = options.getOrDefault("run-id", defaultRunId(generationModel));
            Path dir = Path.of(options.getOrDefault("out", "build/eval-report")).resolve(runId);
            System.out.printf(
                    "[%s] 초안 %s / 평가 %s × %d회 시작%n", runId, generationModel, reviewModel, repeats);
            ReportEvalRunner runner = new ReportEvalRunner(set, llm, JsonMapper.builder().build());
            List<ReportRecord> records =
                    runner.run(
                            repeats,
                            testCase -> cases.isEmpty() || cases.contains(testCase.id()),
                            record ->
                                    System.out.printf(
                                            "  %s#%d %-4s 바퀴=%d 호출=%d 형식실패=%d 위반=%s %dms%n",
                                            record.caseId(),
                                            record.repeat(),
                                            record.displayable() ? "통과" : "실패",
                                            record.rounds().size(),
                                            record.calls().size(),
                                            record.formatFailures(),
                                            record.checkFailures(),
                                            record.wallMs()));

            List<SimilarityProbeResult> probes = List.of();
            String embeddingModel = null;
            Integer embeddingTokens = null;
            if (options.containsKey("embeddings")) {
                HttpTextEmbedder embedder =
                        new HttpTextEmbedder(
                                env.require("EMBEDDING_BASE_URL"),
                                env.require("AI_API_KEY"),
                                env.get("EMBEDDING_MODEL", "text-embedding-3-small"));
                probes = probe(set, embedder);
                embeddingModel = embedder.model();
                embeddingTokens = embedder.promptTokens();
            }

            String report =
                    ReportEvalReport.render(
                            new ReportEvalReport.Meta(
                                    runId,
                                    startedAt,
                                    gitCommit(),
                                    set.setId(),
                                    generationModel,
                                    reviewModel,
                                    repeats),
                            records,
                            prices,
                            probes,
                            embeddingModel,
                            embeddingTokens,
                            env.number("EVAL_EMBEDDING_PRICE_KRW_PER_MTOK"));
            Files.createDirectories(dir);
            Path out = dir.resolve("report.md");
            Files.writeString(out, report, StandardCharsets.UTF_8);
            System.out.println("보고서: " + out.toAbsolutePath());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            contexts.forEach(ConfigurableApplicationContext::close);
        }
    }

    /**
     * 질의 문장과 목표 목록의 유사도를 재고, 같은 기준값으로 RecommendationComposer 가 고를 다음 목표를 본다. 질의와 똑같은 문장은 지금 목표로 보고
     * 뺀다.
     */
    private static List<SimilarityProbeResult> probe(
            ReportRegressionSet set, HttpTextEmbedder embedder) {
        UUID classId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        InMemoryGoalSimilaritySearch search = new InMemoryGoalSimilaritySearch(embedder);
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (String title : set.goalLibrary()) {
            UUID id = UUID.nameUUIDFromBytes(title.getBytes(StandardCharsets.UTF_8));
            ids.put(title, id);
            search.index(classId, id, title);
        }
        List<SimilarityProbeResult> results = new ArrayList<>();
        for (ReportRegressionSet.Probe probe : set.similarityProbes()) {
            UUID self = ids.get(probe.query());
            Set<UUID> excluded = self == null ? Set.of() : Set.of(self);
            List<SimilarGoal> found =
                    search.findSimilar(classId, probe.query(), excluded, set.goalLibrary().size());
            List<SimilarityProbeResult.Row> rows =
                    found.stream()
                            .map(
                                    goal ->
                                            new SimilarityProbeResult.Row(
                                                    goal.title(),
                                                    goal.similarity(),
                                                    expected(probe, goal.title()),
                                                    band(goal.similarity())))
                            .toList();
            String suggestion =
                    found.stream()
                            .filter(
                                    g ->
                                            g.similarity()
                                                    >= RecommendationComposer
                                                            .DEFAULT_MIN_SIMILARITY)
                            .filter(
                                    g ->
                                            g.similarity()
                                                    < RecommendationComposer
                                                            .DEFAULT_DUPLICATE_SIMILARITY)
                            .findFirst()
                            .map(g -> "%s (%.3f)".formatted(g.title(), g.similarity()))
                            .orElse(null);
            results.add(new SimilarityProbeResult(probe.query(), rows, suggestion));
        }
        return results;
    }

    private static String expected(ReportRegressionSet.Probe probe, String title) {
        if (title.equals(probe.duplicate())) return "duplicate";
        if (probe.related().contains(title)) return "related";
        if (probe.unrelated().contains(title)) return "unrelated";
        return "other";
    }

    private static String band(double similarity) {
        if (similarity >= RecommendationComposer.DEFAULT_DUPLICATE_SIMILARITY) return "duplicate";
        if (similarity >= RecommendationComposer.DEFAULT_MIN_SIMILARITY) return "related";
        return "unrelated";
    }

    /**
     * roleplayEval 과 같은 방식으로 Spring AI ChatModel 을 만든다. 모델마다 주소가 달라 모델마다 하나씩 띄운다. 추론 강도와 출력 상한은 모델에
     * 정해 둔 것만 넘긴다(reportPoc 도 쓴다).
     */
    static ConfigurableApplicationContext context(ModelSpec spec, String apiKey, String timeout) {
        List<String> properties =
                new ArrayList<>(
                        List.of(
                                "--spring.ai.model.chat=openai",
                                "--spring.ai.model.embedding=none",
                                "--spring.ai.model.image=none",
                                "--spring.ai.model.audio.speech=none",
                                "--spring.ai.model.audio.transcription=none",
                                "--spring.ai.model.moderation=none",
                                "--spring.ai.openai.base-url=" + spec.baseUrl(),
                                "--spring.ai.openai.api-key=" + apiKey,
                                "--spring.ai.openai.timeout=" + timeout,
                                "--spring.ai.openai.max-retries=0",
                                "--spring.ai.openai.chat.model=" + spec.modelId(),
                                "--neuringo.ai.provider-name=elice-mlapi",
                                "--neuringo.ai.model=" + spec.modelId(),
                                "--neuringo.ai.request-timeout=" + timeout,
                                "--neuringo.ai.max-retries=0",
                                "--logging.level.root=WARN"));
        if (spec.maxOutputTokens() != null) {
            properties.add("--neuringo.ai.max-output-tokens=" + spec.maxOutputTokens());
        }
        if (spec.reasoningEffort() != null) {
            properties.add("--neuringo.ai.reasoning-effort=" + spec.reasoningEffort());
        }
        return new SpringApplicationBuilder(RoleplayEvalMain.EvalApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(properties.toArray(String[]::new));
    }

    private static Price price(EvalEnv env, String input, String output) {
        Double in = env.number(input);
        Double out = env.number(output);
        return in == null || out == null ? null : new Price(in, out);
    }

    /**
     * 측정할 모델 하나. 엘리스 ML API 는 모델마다 호출 주소가 달라서 이름별로 주소·모델 ID·단가를 .env.local 에 둔다.
     *
     * <pre>
     * MODEL_HAIKU55_URL=https://mlapi.run/{모델 페이지 API 탭의 ID}/v1
     * MODEL_HAIKU55_ID=anthropic/claude-haiku-5-5
     * MODEL_HAIKU55_PRICE=141,708      1M 토큰당 원(입력,출력)
     * </pre>
     */
    record ModelSpec(
            String baseUrl,
            String modelId,
            Price price,
            Integer maxOutputTokens,
            String reasoningEffort) {

        static ModelSpec of(EvalEnv env, String name) {
            Integer defaultMaxTokens = integer(env.get("AI_MAX_OUTPUT_TOKENS", null));
            String defaultReasoning = env.get("AI_REASONING_EFFORT", null);
            if (name == null || name.isBlank() || "default".equals(name)) {
                return new ModelSpec(
                        env.require("AI_BASE_URL"),
                        env.require("AI_MODEL"),
                        ReportEvalMain.price(
                                env,
                                "EVAL_PRICE_INPUT_KRW_PER_MTOK",
                                "EVAL_PRICE_OUTPUT_KRW_PER_MTOK"),
                        defaultMaxTokens,
                        defaultReasoning);
            }
            String prefix =
                    "MODEL_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_";
            String[] prices = env.get(prefix + "PRICE", "").split("\\s*,\\s*");
            Price price =
                    prices.length == 2
                            ? new Price(
                                    Double.parseDouble(prices[0]), Double.parseDouble(prices[1]))
                            : null;
            Integer maxTokens = integer(env.get(prefix + "MAX_TOKENS", null));
            return new ModelSpec(
                    env.require(prefix + "URL"),
                    env.require(prefix + "ID"),
                    price,
                    maxTokens == null ? defaultMaxTokens : maxTokens,
                    env.get(prefix + "REASONING", defaultReasoning));
        }

        private static Integer integer(String value) {
            return value == null ? null : Integer.valueOf(value);
        }
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.of(value.split("\\s*,\\s*"));
    }

    private static String defaultRunId(String model) {
        String stamp =
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
        return "report-" + model.replaceAll("[^A-Za-z0-9.-]", "_") + "-" + stamp;
    }

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
}
