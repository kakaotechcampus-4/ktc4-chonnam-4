package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.report.ReportQualityRules;
import com.neuringo.neuringobe.ai.application.report.RetryingLearningReportGenerator;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.eval.ReportEvalMain.ModelSpec;
import com.neuringo.neuringobe.ai.eval.ReportEvalReport.Price;
import com.neuringo.neuringobe.report.application.LearningReportComposer;
import com.neuringo.neuringobe.report.application.LearningReportInputAssembler;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 다른 패키지의 실험 도구가 실제 모델로 리포트를 만들 때 쓰는 입구. .env.local 의 MODEL_{이름}_* 로 초안·평가 모델을 고른다. 키·주소는 밖으로 내지
 * 않는다.
 */
public final class EvalReportComposers implements AutoCloseable {

    private final LearningReportComposer composer;
    private final RecordingLlmProvider recorder;
    private final Map<AiOperation, Price> prices = new LinkedHashMap<>();
    private final ConfigurableApplicationContext genContext;
    private final ConfigurableApplicationContext reviewContext;
    private final String models;

    private EvalReportComposers(Path envFile, String genName, String reviewName) {
        EvalEnv env = EvalEnv.load(envFile);
        ModelSpec gen = ModelSpec.of(env, genName);
        ModelSpec review = ModelSpec.of(env, reviewName);
        String key = env.require("AI_API_KEY");
        String timeout = env.get("AI_TIMEOUT", "60s");
        genContext = ReportEvalMain.context(gen, key, timeout);
        reviewContext =
                genName.equals(reviewName)
                        ? genContext
                        : ReportEvalMain.context(review, key, timeout);
        recorder =
                new RecordingLlmProvider(
                        new RoutingLlmProvider(
                                genContext.getBean(LlmProvider.class),
                                reviewContext.getBean(LlmProvider.class)));
        prices.put(AiOperation.REPORT_GENERATION, gen.price());
        prices.put(AiOperation.REPORT_REVIEW, review.price());
        composer =
                new LearningReportComposer(
                        new StructuredLlmExecutor(recorder),
                        new LearningReportPromptFactory(
                                JsonMapper.builder().build(), ReportLimits.DEFAULT),
                        new RetryingLearningReportGenerator(
                                ReportQualityRules.defaults(ReportLimits.DEFAULT)),
                        new LearningReportInputAssembler());
        models = gen.modelId() + " / " + review.modelId();
    }

    public static EvalReportComposers fromEnv(Path envFile, String genName, String reviewName) {
        return new EvalReportComposers(envFile, genName, reviewName);
    }

    public LearningReportComposer composer() {
        return composer;
    }

    /** 지금까지 부른 호출의 비용(원). 가격을 모르면 null. */
    public Double costKrw() {
        return ReportEvalReport.cost(recorder.since(0), prices);
    }

    public int calls() {
        return recorder.since(0).size();
    }

    public String models() {
        return models;
    }

    @Override
    public void close() {
        genContext.close();
        if (reviewContext != genContext) {
            reviewContext.close();
        }
    }
}
