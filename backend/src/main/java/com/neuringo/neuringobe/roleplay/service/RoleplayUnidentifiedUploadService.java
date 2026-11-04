package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.roleplay.application.RoleplayTurnResponseMapper;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public class RoleplayUnidentifiedUploadService {
    private final RoleplayRequestScopeService scopes;
    private final RoleplayVoiceFailureStore failures;
    private final RoleplayTurnResponseMapper responses;
    private final RoleplayTurnOutcomeResolver outcomes;
    private final RoleplayRetentionGate retentionGate;

    public RoleplayUnidentifiedUploadService(
            RoleplayRequestScopeService scopes,
            RoleplayVoiceFailureStore failures,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            RoleplayRetentionGate retentionGate) {
        this.scopes = scopes;
        this.failures = failures;
        this.responses = responses;
        this.outcomes = outcomes;
        this.retentionGate = retentionGate;
    }

    public RoleplayTurnResponse record(
            Authentication authentication,
            UUID sessionId,
            UUID key,
            RoleplayTurnDeadline deadline) {
        var scope = scopes.loadForChildSession(authentication, sessionId);
        try (var lease = retentionGate.enterInput(sessionId, deadline)) {
            return recordOwned(scope, key, deadline);
        } catch (RoleplayTurnDeadline.Expired expired) {
            return timeout();
        }
    }

    public RoleplayTurnResponse recordOwned(
            RoleplayRequestScopeService.Scope scope, UUID key, RoleplayTurnDeadline deadline) {
        // 지문 없는 요청은 식별자 형식만 채우며 저장소에는 null로 기록한다.
        var identity =
                new RoleplayRequestIdentity(
                        scope.trace(UUID.randomUUID(), UUID.randomUUID()),
                        key,
                        "0".repeat(64),
                        scope.checkpointVersion(),
                        Math.addExact(scope.lastTurnNumber(), 1));
        return switch (failures.recordUnidentifiedUpload(identity, deadline)) {
            case RoleplayVoiceFailureStore.Recorded recorded ->
                    responses.map(
                            new RoleplayCheckpointedTurnExecutor.InputRecovery(
                                    outcomes.inputRecovery(),
                                    recorded.currentProgress().textRecommended(),
                                    false));
            case RoleplayVoiceFailureStore.Conflict ignored ->
                    responses.map(new RoleplayCheckpointedTurnExecutor.Conflict());
            case RoleplayVoiceFailureStore.Expired ignored -> timeout();
            case RoleplayVoiceFailureStore.Absent ignored ->
                    throw new IllegalStateException("음성 실패 저장 결과가 누락됐습니다.");
            case RoleplayVoiceFailureStore.PolicyRequired ignored ->
                    throw new IllegalStateException("음성 실패 집계 정책이 누락됐습니다.");
        };
    }

    private RoleplayTurnResponse timeout() {
        return responses.map(
                new RoleplayCheckpointedTurnExecutor.Recovery(
                        outcomes.resolve(new RoleplayTurnResult.TimedOut())));
    }
}
