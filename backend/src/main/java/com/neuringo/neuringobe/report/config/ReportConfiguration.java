package com.neuringo.neuringobe.report.config;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.report.ReportQualityRules;
import com.neuringo.neuringobe.ai.application.report.RetryingLearningReportGenerator;
import com.neuringo.neuringobe.report.application.LearningReportInputAssembler;
import com.neuringo.neuringobe.report.application.RoleplayAnswerMapper;
import com.neuringo.neuringobe.report.domain.RecommendationPolicy;
import com.neuringo.neuringobe.report.domain.RecommendationRules;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayGoalSummarizer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/** 리포트 준비·생성에 쓰는 순수 계산 객체와 생성 전용 스레드를 만든다. LLM 제공자는 없을 수 있어서 생성 서비스가 그때그때 찾는다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ReportProperties.class)
public class ReportConfiguration {

    @Bean
    RoleplayGoalSummarizer roleplayGoalSummarizer() {
        return new RoleplayGoalSummarizer();
    }

    @Bean
    RoleplayAnswerMapper roleplayAnswerMapper() {
        return new RoleplayAnswerMapper();
    }

    @Bean
    LearningReportInputAssembler learningReportInputAssembler() {
        return new LearningReportInputAssembler();
    }

    @Bean
    RecommendationPolicy recommendationPolicy() {
        return new RecommendationPolicy(RecommendationRules.V1);
    }

    @Bean
    LearningReportPromptFactory learningReportPromptFactory(JsonMapper mapper) {
        return new LearningReportPromptFactory(mapper, ReportLimits.DEFAULT);
    }

    @Bean
    RetryingLearningReportGenerator retryingLearningReportGenerator() {
        return new RetryingLearningReportGenerator(
                ReportQualityRules.defaults(ReportLimits.DEFAULT));
    }

    /** 데몬 스레드라 서버가 꺼지면 진행 중인 생성은 버려지고, 리포트는 PENDING 으로 남아 재처리 작업이 다시 넣는다. */
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor reportGenerationWorkers(ReportProperties properties) {
        var sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                properties.workers(),
                properties.workers(),
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                task -> {
                    var thread =
                            new Thread(task, "report-generation-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
