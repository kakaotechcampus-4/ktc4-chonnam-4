package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.roleplay.application.*;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import com.neuringo.neuringobe.roleplay.service.RoleplayVoiceInputService;
import jakarta.servlet.MultipartConfigElement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
@EnableConfigurationProperties(RoleplayHttpProperties.class)
public class RoleplayHttpConfiguration {
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
    public MultipartConfigElement multipartConfigElement(RoleplayHttpProperties p) {
        return new MultipartConfigElement(
                p.spoolDirectory().toString(), p.maximumUploadBytes(), p.maximumRequestBytes(), 0);
    }

    @Bean
    public RoleplayVoiceInputService roleplayVoiceInputService(
            RoleplayHttpProperties p,
            RoleplayRequestScopeService scopes,
            @Qualifier("roleplayTurnWorkers") ThreadPoolExecutor roleplayTurnWorkers,
            RoleplayCheckpointStore store,
            RoleplayRequestGuard guard,
            RoleplayContextSource source,
            RoleplayInputProcessor inputProcessor,
            LlmProvider llm,
            SpeechToTextProvider stt,
            TextToSpeechProvider tts,
            RoleplaySpeechDelivery speechDelivery,
            JsonMapper mapper) {
        var outcomes = new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(p.notices()));
        var checkpoints =
                new RoleplayCheckpointedTurnExecutor(
                        new RoleplayTurnRunner(roleplayTurnWorkers), store, outcomes, guard);
        var speech =
                new RoleplaySpeechTurnPipeline(
                        stt,
                        tts,
                        inputProcessor,
                        new RoleplayPromptFactory(mapper, null),
                        new StructuredLlmExecutor(llm));
        var turns =
                new RoleplayScopedVoiceTurnExecutor(
                        checkpoints, new RoleplayContextAssembler(source), speech);
        var uploads =
                new RoleplayUploadSpoolFactory(
                        p.spoolDirectory(),
                        new RoleplayUploadPolicy(p.maximumUploadBytes(), p.allowedFormats()));
        return new RoleplayVoiceInputService(
                scopes, uploads, turns, new RoleplayTurnResponseMapper(speechDelivery), outcomes);
    }
}
