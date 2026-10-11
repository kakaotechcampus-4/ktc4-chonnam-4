package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.roleplay.application.RoleplayScopedVoiceTurnExecutor;
import com.neuringo.neuringobe.roleplay.application.RoleplayTurnResponseMapper;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * Explicitly assembled BE entry. No controller, default provider beans or long-lived transaction.
 */
public final class RoleplayVoiceInputService {
    private final RoleplayRequestScopeService scopes;
    private final RoleplayUploadSpoolFactory uploads;
    private final RoleplayScopedVoiceTurnExecutor turns;
    private final RoleplayTurnResponseMapper responses;
    private final RoleplayTurnOutcomeResolver outcomes;

    public RoleplayVoiceInputService(
            RoleplayRequestScopeService scopes,
            RoleplayUploadSpoolFactory uploads,
            RoleplayScopedVoiceTurnExecutor turns,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes) {
        this.scopes = Objects.requireNonNull(scopes);
        this.uploads = Objects.requireNonNull(uploads);
        this.turns = Objects.requireNonNull(turns);
        this.responses = Objects.requireNonNull(responses);
        this.outcomes = Objects.requireNonNull(outcomes);
    }

    /**
     * Takes source ownership, including authentication failures. Caller supplies the shared
     * deadline and verified Spring authentication.
     */
    public RoleplayTurnResponse submitVoice(
            Authentication authentication,
            UUID activityId,
            UUID sessionId,
            UUID idempotencyKey,
            InputStream source,
            String contentType,
            RoleplayTurnDeadline deadline) {
        return submit(
                () -> scopes.loadForChild(authentication, activityId, sessionId),
                idempotencyKey,
                source,
                contentType,
                deadline);
    }

    /** HTTP session-only path resolves activity ownership from server storage. */
    public RoleplayTurnResponse submitVoice(
            Authentication authentication,
            UUID sessionId,
            UUID idempotencyKey,
            InputStream source,
            String contentType,
            RoleplayTurnDeadline deadline) {
        return submit(
                () -> scopes.loadForChildSession(authentication, sessionId),
                idempotencyKey,
                source,
                contentType,
                deadline);
    }

    private RoleplayTurnResponse submit(
            java.util.function.Supplier<RoleplayRequestScopeService.Scope> scopeLookup,
            UUID idempotencyKey,
            InputStream source,
            String contentType,
            RoleplayTurnDeadline deadline) {
        try (var input = new CloseOnceInputStream(Objects.requireNonNull(source))) {
            Objects.requireNonNull(deadline).requireActive();
            Objects.requireNonNull(idempotencyKey);
            var scope = scopeLookup.get();
            deadline.requireActive();
            UUID requestId = UUID.randomUUID(), turnId = UUID.randomUUID();
            var audio =
                    uploads.create(input, contentType, scope.trace(requestId, turnId), deadline);
            var result =
                    turns.executeVoice(scope, requestId, turnId, idempotencyKey, audio, deadline);
            return responses.map(result);
        } catch (RoleplayTurnDeadline.Expired expired) {
            if (expired.getSuppressed().length > 0) throw expired;
            return responses.map(
                    new RoleplayCheckpointedTurnExecutor.Recovery(
                            outcomes.resolve(new RoleplayTurnResult.TimedOut())));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not close voice input", ex);
        }
    }

    /**
     * The spool factory also closes its input. Ensure one close of the original stream, even if
     * close itself fails.
     */
    private static final class CloseOnceInputStream extends FilterInputStream {
        private boolean closed;

        private CloseOnceInputStream(InputStream source) {
            super(source);
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                super.close();
            }
        }
    }
}
