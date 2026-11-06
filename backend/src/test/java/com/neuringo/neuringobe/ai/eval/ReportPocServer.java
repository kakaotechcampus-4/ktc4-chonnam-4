package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.report.LearningReportResult;
import com.neuringo.neuringobe.ai.application.report.ReportQualityRules;
import com.neuringo.neuringobe.ai.application.report.RetryingLearningReportGenerator;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import com.neuringo.neuringobe.ai.eval.ReportEvalMain.ModelSpec;
import com.neuringo.neuringobe.ai.eval.ReportEvalReport.Price;
import com.neuringo.neuringobe.report.application.ComposedReport;
import com.neuringo.neuringobe.report.application.LearningReportComposer;
import com.neuringo.neuringobe.report.application.LearningReportInputAssembler;
import com.neuringo.neuringobe.report.application.Recommendation;
import com.neuringo.neuringobe.report.application.RecommendationComposer;
import com.neuringo.neuringobe.report.application.RecommendationRequest;
import com.neuringo.neuringobe.report.application.ReportRequest;
import com.neuringo.neuringobe.report.application.port.SimilarGoal;
import com.neuringo.neuringobe.report.application.similarity.InMemoryGoalSimilaritySearch;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import com.neuringo.neuringobe.report.domain.Ratio;
import com.neuringo.neuringobe.report.domain.RecommendationPolicy;
import com.neuringo.neuringobe.report.domain.RecommendationRules;
import com.neuringo.neuringobe.report.domain.ReportEvidence;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyPolicy;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyRules;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 학습 리포트·추천·후속 난이도 PoC 서버. {@code ./gradlew reportPoc} 로 띄우고 http://127.0.0.1:8787 을 연다.
 *
 * <p>회귀 세트의 합성 사례 하나를 운영과 같은 경로(지표 → 입력 조립 → 프롬프트 v2 → 생성·평가 루프)로 실제 모델에 돌리고, 강사에게 보일 리포트와 그 뒤의 과정을
 * 한 화면에 보여 준다. 저장·인증·DB 는 없다. 127.0.0.1 에만 열고, 키·엔드포인트 주소는 화면으로 보내지 않는다.
 */
public final class ReportPocServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID CLASS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");

    private final EvalEnv env;
    private final ReportRegressionSet set;
    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final HttpTextEmbedder embedder;
    private final InMemoryGoalSimilaritySearch search;
    private final Map<String, UUID> goalIds = new LinkedHashMap<>();
    private boolean indexed;

    private ReportPocServer(EvalEnv env, ReportRegressionSet set) {
        this.env = env;
        this.set = set;
        String embeddingUrl = env.get("EMBEDDING_BASE_URL", null);
        this.embedder =
                embeddingUrl == null
                        ? null
                        : new HttpTextEmbedder(
                                embeddingUrl,
                                env.require("AI_API_KEY"),
                                env.get("EMBEDDING_MODEL", "text-embedding-3-small"));
        this.search = embedder == null ? null : new InMemoryGoalSimilaritySearch(embedder);
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = RoleplayEvalMain.parse(args);
        int port = Integer.parseInt(options.getOrDefault("port", "8787"));
        ReportPocServer poc =
                new ReportPocServer(
                        EvalEnv.load(Path.of(options.getOrDefault("env", ".env.local"))),
                        ReportRegressionSet.load(ReportRegressionSet.DEFAULT_RESOURCE));
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", poc::page);
        server.createContext("/api/options", poc::options);
        server.createContext("/api/run", poc::run);
        server.setExecutor(Executors.newFixedThreadPool(4));
        Runtime.getRuntime().addShutdownHook(new Thread(poc::close));
        server.start();
        System.out.println("PoC: http://127.0.0.1:" + port + "/");
    }

    private void page(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            send(
                    exchange,
                    404,
                    "text/plain; charset=utf-8",
                    "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in =
                ReportPocServer.class.getClassLoader().getResourceAsStream("poc/report-poc.html")) {
            send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private void options(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> cases =
                set.cases().stream()
                        .map(c -> Map.<String, Object>of("id", c.id(), "purpose", c.purpose()))
                        .toList();
        List<Map<String, Object>> models =
                env.modelNames().stream()
                        .map(
                                name -> {
                                    Map<String, Object> model = new LinkedHashMap<>();
                                    model.put("name", name);
                                    model.put(
                                            "id",
                                            env.get(
                                                    "MODEL_"
                                                            + name.toUpperCase(Locale.ROOT)
                                                            + "_ID",
                                                    ""));
                                    return model;
                                })
                        .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cases", cases);
        body.put("models", models);
        body.put(
                "defaults",
                Map.of(
                        "gen", env.get("POC_GEN", "haiku55"),
                        "review", env.get("POC_REVIEW", "sonnet55")));
        json(exchange, 200, body);
    }

    private void run(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            json(exchange, 405, Map.of("error", "POST only"));
            return;
        }
        try {
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            json(exchange, 200, execute(request));
        } catch (RuntimeException exception) {
            json(
                    exchange,
                    500,
                    Map.of(
                            "error",
                            exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        }
    }

    private synchronized Map<String, Object> execute(JsonNode request) {
        ReportRegressionSet.Case testCase =
                set.cases().stream()
                        .filter(c -> c.id().equals(request.get("caseId").asString()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("unknown case"));
        String genName = request.get("gen").asString();
        String reviewName = request.get("review").asString();
        ModelSpec gen = ModelSpec.of(env, genName);
        ModelSpec review = ModelSpec.of(env, reviewName);
        RecordingLlmProvider recorder =
                new RecordingLlmProvider(
                        new RoutingLlmProvider(
                                provider(genName, gen), provider(reviewName, review)));
        LearningReportPromptFactory prompts =
                new LearningReportPromptFactory(JSON, ReportLimits.DEFAULT);
        LearningReportComposer composer =
                new LearningReportComposer(
                        new StructuredLlmExecutor(recorder),
                        prompts,
                        new RetryingLearningReportGenerator(
                                ReportQualityRules.defaults(ReportLimits.DEFAULT)),
                        new LearningReportInputAssembler());

        ReportEvidence evidence = testCase.evidence();
        long startedAt = System.nanoTime();
        ComposedReport composed =
                composer.compose(
                        new ReportRequest(
                                UUID.randomUUID(),
                                UUID.nameUUIDFromBytes(
                                        testCase.id().getBytes(StandardCharsets.UTF_8)),
                                CLASS_ID,
                                CHILD_ID,
                                evidence));
        long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
        LearningReportInput input =
                new LearningReportInputAssembler().assemble(evidence, composed.metrics());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evidence", evidence(input));
        body.put("metrics", metrics(composed.metrics()));
        body.put(
                "difficulty",
                difficulty(
                        new FollowUpDifficultyPolicy(FollowUpDifficultyRules.V1)
                                .decide(
                                        ScenarioLevel.valueOf(
                                                request.get("currentLevel").asString()),
                                        evidence.goals().stream()
                                                .map(GoalEvidence::outcome)
                                                .toList(),
                                        request.get("previousCandidates").asInt())));
        List<RecordingLlmProvider.Call> calls = recorder.since(0);
        body.put("result", result(composed.result(), calls, input, prompts));
        String currentGoal = request.get("currentGoal").asString();
        body.putAll(recommendations(evidence, currentGoal));
        Map<AiOperation, Price> prices = new LinkedHashMap<>();
        prices.put(AiOperation.REPORT_GENERATION, gen.price());
        prices.put(AiOperation.REPORT_REVIEW, review.price());
        body.put("calls", calls(calls, prices, input, prompts));
        Double cost = ReportEvalReport.cost(calls, prices);
        body.put("totalCostKrw", cost == null ? 0.0 : cost);
        body.put("totalMs", totalMs);
        body.put(
                "promptVersions",
                List.of(
                        LearningReportPromptFactory.GENERATION_PROMPT_VERSION,
                        LearningReportPromptFactory.REVIEW_PROMPT_VERSION));
        return body;
    }

    private LlmProvider provider(String name, ModelSpec spec) {
        return providers.computeIfAbsent(
                name,
                ignored -> {
                    ConfigurableApplicationContext context =
                            ReportEvalMain.context(
                                    spec, env.require("AI_API_KEY"), env.get("AI_TIMEOUT", "60s"));
                    synchronized (contexts) {
                        contexts.add(context);
                    }
                    return context.getBean(LlmProvider.class);
                });
    }

    private static List<Map<String, Object>> evidence(LearningReportInput input) {
        return input.evidence().stream()
                .map(
                        e -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", e.id());
                            item.put("kind", e.kind().name());
                            item.put("goal", e.goalTitle());
                            item.put("text", e.text());
                            item.put(
                                    "status",
                                    e.goalStatus() == null ? null : e.goalStatus().name());
                            return item;
                        })
                .toList();
    }

    private static Map<String, Object> metrics(LearningMetrics metrics) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quizAccuracy", ratio(metrics.quizAccuracy()));
        out.put("quizHintRecovery", ratio(metrics.quizHintRecovery()));
        out.put("roleplayScore", ratio(metrics.roleplayScore()));
        out.put("goalAchievement", ratio(metrics.goalAchievement()));
        out.put("independentAchievement", ratio(metrics.independentAchievement()));
        return out;
    }

    private static Map<String, Object> ratio(Ratio ratio) {
        if (ratio == null) return null;
        return Map.of(
                "numerator", ratio.numerator(),
                "denominator", ratio.denominator(),
                "value", ratio.value());
    }

    private static Map<String, Object> difficulty(FollowUpDifficultyDecision decision) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("previousLevel", decision.previousLevel().name());
        out.put("nextLevel", decision.nextLevel().name());
        out.put("direction", decision.direction().name());
        out.put("reason", decision.reason().name());
        out.put("scoreRatio", decision.scoreRate().ratio());
        out.put("validGoals", decision.scoreRate().goalCount());
        out.put("consecutiveUpgradeCandidates", decision.consecutiveUpgradeCandidates());
        out.put("policyVersion", decision.policyVersion());
        return out;
    }

    private static Map<String, Object> result(
            LearningReportResult result,
            List<RecordingLlmProvider.Call> calls,
            LearningReportInput input,
            LearningReportPromptFactory prompts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rounds", result.rounds());
        if (result instanceof LearningReportResult.Completed completed) {
            out.put("status", "COMPLETED");
            out.put("draft", completed.draft());
            out.put("itemChecks", completed.review().itemChecks());
        } else {
            LearningReportResult.Failed failed = (LearningReportResult.Failed) result;
            out.put("status", "FAILED");
            out.put("reason", failed.reason().name());
            out.put("operation", failed.operation().name());
            out.put(
                    "draft",
                    lastParsed(calls, AiOperation.REPORT_GENERATION, draftParser(prompts, input)));
            ReportReview review =
                    lastParsed(calls, AiOperation.REPORT_REVIEW, reviewParser(prompts, input));
            out.put("itemChecks", review == null ? List.of() : review.itemChecks());
        }
        return out;
    }

    private Map<String, Object> recommendations(ReportEvidence evidence, String currentGoal) {
        List<SimilarGoal> similar = List.of();
        String similarityError = null;
        UUID currentGoalId = UUID.nameUUIDFromBytes(currentGoal.getBytes(StandardCharsets.UTF_8));
        boolean searchReady = false;
        if (search != null) {
            // 임베딩 호출이 실패해도 리포트·난이도·추천은 보여 주고, 유사도 칸에만 실패를 적는다.
            try {
                indexGoalLibrary();
                similar = search.findSimilar(CLASS_ID, currentGoal, Set.of(currentGoalId), 8);
                searchReady = true;
            } catch (RuntimeException exception) {
                similarityError = exception.getMessage();
            }
        }
        RecommendationComposer composer =
                new RecommendationComposer(
                        new RecommendationPolicy(RecommendationRules.V1),
                        searchReady ? search : (classId, query, excluded, limit) -> List.of());
        List<Recommendation> recommendations =
                composer.compose(
                        new RecommendationRequest(
                                CLASS_ID, currentGoalId, currentGoal, Set.of(), evidence.goals()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
                "recommendations",
                recommendations.stream()
                        .map(
                                r -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("type", r.candidate().type().name());
                                    item.put("reason", r.candidate().reason().name());
                                    item.put(
                                            "basisGoal",
                                            r.candidate().basis() == null
                                                    ? null
                                                    : r.candidate().basis().goalTitle());
                                    item.put("evidence", r.candidate().evidence());
                                    item.put("sufficiency", r.candidate().sufficiency().name());
                                    item.put("suggestedGoal", r.suggestedGoal());
                                    return item;
                                })
                        .toList());
        out.put("similarity", similar);
        out.put("similarityError", similarityError);
        return out;
    }

    /** 실패하면 indexed 가 false 로 남아 다음 실행에서 다시 색인한다. 이미 받은 벡터는 HttpTextEmbedder 가 기억한다. */
    private void indexGoalLibrary() {
        if (indexed) return;
        for (String title : set.goalLibrary()) {
            UUID id = UUID.nameUUIDFromBytes(title.getBytes(StandardCharsets.UTF_8));
            goalIds.put(title, id);
            search.index(CLASS_ID, id, title);
        }
        indexed = true;
    }

    private static List<Map<String, Object>> calls(
            List<RecordingLlmProvider.Call> calls,
            Map<AiOperation, Price> prices,
            LearningReportInput input,
            LearningReportPromptFactory prompts) {
        LlmOutputParser<ReportDraft> draft = draftParser(prompts, input);
        LlmOutputParser<ReportReview> review = reviewParser(prompts, input);
        return calls.stream()
                .map(
                        call -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("operation", call.operation().name());
                            item.put("model", call.model());
                            item.put("ms", call.wallMs());
                            item.put("inputTokens", call.inputTokens());
                            item.put("outputTokens", call.outputTokens());
                            item.put("finishReason", call.finishReason());
                            item.put(
                                    "formatFailure",
                                    call.content() != null
                                            && !parses(
                                                    call.operation() == AiOperation.REPORT_REVIEW
                                                            ? review
                                                            : draft,
                                                    call.content()));
                            Double cost = ReportEvalReport.cost(List.of(call), prices);
                            item.put("costKrw", cost == null ? 0.0 : cost);
                            return item;
                        })
                .toList();
    }

    private static LlmOutputParser<ReportDraft> draftParser(
            LearningReportPromptFactory prompts, LearningReportInput input) {
        return prompts.generation(trace(), new AiAttemptContext(0, 1, 0), 1, input, null).parser();
    }

    private static LlmOutputParser<ReportReview> reviewParser(
            LearningReportPromptFactory prompts, LearningReportInput input) {
        return prompts.review(
                        trace(),
                        new AiAttemptContext(0, 1, 1),
                        1,
                        input,
                        new ReportDraft("요약", List.of(), List.of(), List.of()))
                .parser();
    }

    private static AiTraceContext trace() {
        return new AiTraceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                CHILD_ID,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static <T> T lastParsed(
            List<RecordingLlmProvider.Call> calls,
            AiOperation operation,
            LlmOutputParser<T> parser) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            RecordingLlmProvider.Call call = calls.get(i);
            if (call.operation() != operation || call.content() == null) continue;
            try {
                return parser.parse(call.content());
            } catch (InvalidLlmOutputException exception) {
                // 형식이 틀린 응답은 건너뛰고 그 앞의 응답을 본다.
            }
        }
        return null;
    }

    private static boolean parses(LlmOutputParser<?> parser, String content) {
        try {
            parser.parse(content);
            return true;
        } catch (InvalidLlmOutputException exception) {
            return false;
        }
    }

    private void close() {
        synchronized (contexts) {
            contexts.forEach(ConfigurableApplicationContext::close);
        }
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
