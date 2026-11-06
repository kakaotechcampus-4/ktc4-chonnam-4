package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.roleplay.application.*;
import com.neuringo.neuringobe.roleplay.application.RoleplayAudioInspector;
import com.neuringo.neuringobe.roleplay.controller.RoleplayUploadDeadlineFilter;
import com.neuringo.neuringobe.roleplay.infrastructure.input.FfmpegRoleplayAudioInspector;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayOwnedSpool;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import com.neuringo.neuringobe.roleplay.service.RoleplayCompletionCheckpointStore;
import com.neuringo.neuringobe.roleplay.service.RoleplayCompletionService;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import com.neuringo.neuringobe.roleplay.service.RoleplayRetentionGate;
import com.neuringo.neuringobe.roleplay.service.RoleplayUnidentifiedUploadService;
import com.neuringo.neuringobe.roleplay.service.RoleplayVoiceInputService;
import jakarta.servlet.MultipartConfigElement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
@EnableConfigurationProperties({
    RoleplayHttpProperties.class,
    RoleplayInputProperties.class,
    RoleplayMediaProperties.class
})
public class RoleplayHttpConfiguration {
    @Bean
    @ConditionalOnMissingBean(RoleplayRetentionGate.class)
    public RoleplayRetentionGate roleplayRetentionGate() {
        return new RoleplayRetentionGate();
    }

    @Bean
    public RoleplayUploadDeadlineFilter roleplayUploadDeadlineFilter(
            RoleplayHttpProperties properties) {
        return new RoleplayUploadDeadlineFilter(properties.turnBudget());
    }

    @Bean
    @ConditionalOnMissingBean(RoleplayAudioInspector.class)
    public RoleplayAudioInspector roleplayAudioInspector(
            RoleplayMediaProperties media, RoleplayHttpProperties http) {
        return new FfmpegRoleplayAudioInspector(
                media.ffmpegExecutable(),
                media.ffprobeExecutable(),
                media.inspectionTimeout(),
                http.workerCount());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ThreadPoolExecutor roleplayTurnWorkers(RoleplayHttpProperties p) {
        var sequence = new AtomicInteger();
        BlockingQueue<Runnable> queue =
                p.queueCapacity() == 0
                        ? new SynchronousQueue<>()
                        : new ArrayBlockingQueue<>(p.queueCapacity());
        return new ThreadPoolExecutor(
                p.workerCount(),
                p.workerCount(),
                0,
                TimeUnit.MILLISECONDS,
                queue,
                task -> {
                    var thread = new Thread(task, "roleplay-turn-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public MultipartConfigElement multipartConfigElement(
            RoleplayHttpProperties p, RoleplayOwnedSpool spool) {
        return new MultipartConfigElement(
                spool.directory().toString(), p.maximumUploadBytes(), p.maximumRequestBytes(), 0);
    }

    @Bean(destroyMethod = "")
    public RoleplayOwnedSpool roleplayOwnedSpool(RoleplayHttpProperties properties)
            throws java.io.IOException {
        return RoleplayOwnedSpool.createForProcess(properties.spoolDirectory());
    }

    @Bean
    public RoleplayTurnOutcomeResolver roleplayTurnOutcomes(RoleplayHttpProperties properties) {
        return new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(properties.notices()));
    }

    @Bean
    public RoleplayCheckpointedTurnExecutor roleplayCheckpoints(
            @Qualifier("roleplayTurnWorkers") ThreadPoolExecutor workers,
            RoleplayCheckpointStore store,
            RoleplayRequestGuard guard,
            RoleplayInputFailureStore inputFailures,
            RoleplayActivityGoalEvaluator goals,
            RoleplayCompletionService completion,
            RoleplayTurnOutcomeResolver outcomes,
            RoleplayProgressProperties progress) {
        if (!progress.configured()) {
            throw new IllegalStateException("역할극 HTTP API를 켜려면 전체·목표별 턴 제한을 함께 설정해야 합니다.");
        }
        return new RoleplayCheckpointedTurnExecutor(
                new RoleplayTurnRunner(workers),
                new RoleplayCompletionCheckpointStore(store, goals, completion),
                outcomes,
                guard,
                inputFailures);
    }

    @Bean
    public RoleplaySpeechTurnPipeline roleplaySpeechPipeline(
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplayInputProcessor inputProcessor,
            LlmProvider llm,
            JsonMapper mapper,
            RoleplayInputProperties inputProperties) {
        return new RoleplaySpeechTurnPipeline(
                stt,
                tts,
                inputProcessor,
                new RoleplayPromptFactory(mapper, null),
                new StructuredLlmExecutor(llm),
                inputProperties.sttConfidenceThreshold());
    }

    @Bean
    public RoleplayTurnResponseMapper roleplayTurnResponses(RoleplaySpeechDelivery delivery) {
        return new RoleplayTurnResponseMapper(delivery);
    }

    @Bean
    public RoleplayVoiceInputService roleplayVoiceInputService(
            RoleplayHttpProperties p,
            RoleplayRequestScopeService scopes,
            RoleplayCheckpointedTurnExecutor checkpoints,
            RoleplayContextSource source,
            RoleplaySpeechTurnPipeline speech,
            RoleplayVoiceRecoverySource recoveries,
            org.springframework.beans.factory.ObjectProvider<RoleplayUnidentifiedUploadService>
                    unidentifiedUploads,
            RoleplayOwnedSpool spool,
            RoleplayRetentionGate retentionGate,
            RoleplayAudioInspector inspector,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes) {
        var turns =
                new RoleplayScopedVoiceTurnExecutor(
                        checkpoints, new RoleplayContextAssembler(source), speech, recoveries);
        var uploads =
                new RoleplayUploadSpoolFactory(
                        spool.directory(),
                        new RoleplayUploadPolicy(p.maximumUploadBytes(), p.allowedFormats()),
                        inspector);
        return new RoleplayVoiceInputService(
                scopes,
                uploads,
                turns,
                responses,
                outcomes,
                recoveries,
                unidentifiedUploads.getIfAvailable(),
                retentionGate);
    }
}
