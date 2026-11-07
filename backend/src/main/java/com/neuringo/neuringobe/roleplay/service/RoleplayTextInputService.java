package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.application.*;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import java.util.UUID;
import org.springframework.security.core.Authentication;

public final class RoleplayTextInputService {
    private final RoleplayRequestScopeService scopes;
    private final RoleplayScopedTextTurnExecutor turns;
    private final RoleplayTurnResponseMapper responses;
    private final RoleplayTurnOutcomeResolver outcomes;
    private final int maximumCharacters;
    private final RoleplayRetentionGate retentionGate;

    public RoleplayTextInputService(
            RoleplayRequestScopeService scopes,
            RoleplayScopedTextTurnExecutor turns,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            int maximumCharacters) {
        this(scopes, turns, responses, outcomes, maximumCharacters, null);
    }

    public RoleplayTextInputService(
            RoleplayRequestScopeService scopes,
            RoleplayScopedTextTurnExecutor turns,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            int maximumCharacters,
            RoleplayRetentionGate retentionGate) {
        if (maximumCharacters < 1) throw new IllegalArgumentException("텍스트 입력 처리 상한은 양수여야 합니다.");
        this.scopes = scopes;
        this.turns = turns;
        this.responses = responses;
        this.outcomes = outcomes;
        this.maximumCharacters = maximumCharacters;
        this.retentionGate = retentionGate;
    }

    public RoleplayTurnResponse submit(
            Authentication authentication,
            UUID sessionId,
            UUID idempotencyKey,
            String text,
            RoleplayTurnDeadline deadline) {
        try (var ignored =
                retentionGate == null ? null : retentionGate.enterInput(sessionId, deadline)) {
            deadline.requireActive();
            var scope = scopes.loadForChildSession(authentication, sessionId);
            deadline.requireActive();
            if (text.codePointCount(0, text.length()) > maximumCharacters) {
                if (!scope.acceptsNewTurn())
                    return responses.map(new RoleplayCheckpointedTurnExecutor.Conflict());
                return responses.map(
                        new RoleplayCheckpointedTurnExecutor.Recovery(outcomes.inputRecovery()));
            }
            return responses.map(
                    turns.execute(
                            scope,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            idempotencyKey,
                            text,
                            deadline));
        } catch (RoleplayTurnDeadline.Expired expired) {
            return responses.map(
                    new RoleplayCheckpointedTurnExecutor.Recovery(
                            outcomes.resolve(new RoleplayTurnResult.TimedOut())));
        }
    }
}
