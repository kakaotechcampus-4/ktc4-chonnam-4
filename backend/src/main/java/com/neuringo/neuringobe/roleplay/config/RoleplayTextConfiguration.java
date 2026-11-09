package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.application.*;
import com.neuringo.neuringobe.roleplay.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"roleplay.http.enabled", "roleplay.text.enabled"},
        havingValue = "true")
@EnableConfigurationProperties(RoleplayTextProperties.class)
public class RoleplayTextConfiguration {
    @Bean
    public RoleplayTextInputService roleplayTextInputService(
            RoleplayTextProperties properties,
            RoleplayRequestScopeService scopes,
            RoleplayCheckpointedTurnExecutor checkpoints,
            RoleplayContextSource source,
            RoleplayVoiceRecoverySource recoveries,
            RoleplaySpeechTurnPipeline pipeline,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            RoleplayRetentionGate retentionGate) {
        return new RoleplayTextInputService(
                scopes,
                new RoleplayScopedTextTurnExecutor(
                        checkpoints, new RoleplayContextAssembler(source), pipeline, recoveries),
                responses,
                outcomes,
                properties.maximumCharacters(),
                retentionGate);
    }
}
