package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayAudioResource;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.roleplay.application.RoleplayAudioInspector;
import com.neuringo.neuringobe.roleplay.application.RoleplayScopedVoiceTurnExecutor;
import com.neuringo.neuringobe.roleplay.application.RoleplayTurnResponseMapper;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceFailureProgress;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceRecoverySource;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/**
 * Explicitly assembled BE entry. No controller, default provider beans or long-lived transaction.
 */
public final class RoleplayVoiceInputService {
    private static final Logger log = LoggerFactory.getLogger(RoleplayVoiceInputService.class);
    private final RoleplayRequestScopeService scopes;
    private final RoleplayUploadSpoolFactory uploads;
    private final RoleplayScopedVoiceTurnExecutor turns;
    private final RoleplayTurnResponseMapper responses;
    private final RoleplayTurnOutcomeResolver outcomes;
    private final RoleplayVoiceRecoverySource recoveries;
    private final RoleplayUnidentifiedUploadService unidentifiedUploads;
    private final RoleplayRetentionGate retentionGate;

    public RoleplayVoiceInputService(
            RoleplayRequestScopeService scopes,
            RoleplayUploadSpoolFactory uploads,
            RoleplayScopedVoiceTurnExecutor turns,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes) {
        this(
                scopes,
                uploads,
                turns,
                responses,
                outcomes,
                (key, deadline) -> RoleplayVoiceFailureProgress.initial(),
                null);
    }

    public RoleplayVoiceInputService(
            RoleplayRequestScopeService scopes,
            RoleplayUploadSpoolFactory uploads,
            RoleplayScopedVoiceTurnExecutor turns,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            RoleplayVoiceRecoverySource recoveries) {
        this(scopes, uploads, turns, responses, outcomes, recoveries, null);
    }

    public RoleplayVoiceInputService(
            RoleplayRequestScopeService scopes,
            RoleplayUploadSpoolFactory uploads,
            RoleplayScopedVoiceTurnExecutor turns,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            RoleplayVoiceRecoverySource recoveries,
            RoleplayUnidentifiedUploadService unidentifiedUploads) {
        this(scopes, uploads, turns, responses, outcomes, recoveries, unidentifiedUploads, null);
    }

    public RoleplayVoiceInputService(
            RoleplayRequestScopeService scopes,
            RoleplayUploadSpoolFactory uploads,
            RoleplayScopedVoiceTurnExecutor turns,
            RoleplayTurnResponseMapper responses,
            RoleplayTurnOutcomeResolver outcomes,
            RoleplayVoiceRecoverySource recoveries,
            RoleplayUnidentifiedUploadService unidentifiedUploads,
            RoleplayRetentionGate retentionGate) {
        this.scopes = Objects.requireNonNull(scopes);
        this.uploads = Objects.requireNonNull(uploads);
        this.turns = Objects.requireNonNull(turns);
        this.responses = Objects.requireNonNull(responses);
        this.outcomes = Objects.requireNonNull(outcomes);
        this.recoveries = Objects.requireNonNull(recoveries);
        this.unidentifiedUploads = unidentifiedUploads;
        this.retentionGate = retentionGate;
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
                sessionId,
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
                sessionId,
                idempotencyKey,
                source,
                contentType,
                deadline);
    }

    private RoleplayTurnResponse submit(
            java.util.function.Supplier<RoleplayRequestScopeService.Scope> scopeLookup,
            UUID sessionId,
            UUID idempotencyKey,
            InputStream source,
            String contentType,
            RoleplayTurnDeadline deadline) {
        try (var input = new CloseOnceInputStream(Objects.requireNonNull(source));
                var lease =
                        retentionGate == null
                                ? null
                                : retentionGate.enterInput(sessionId, deadline)) {
            Objects.requireNonNull(deadline).requireActive();
            Objects.requireNonNull(idempotencyKey);
            var scope = scopeLookup.get();
            deadline.requireActive();
            UUID requestId = UUID.randomUUID(), turnId = UUID.randomUUID();
            RoleplayAudioResource audio;
            try {
                audio =
                        uploads.create(
                                input, contentType, scope.trace(requestId, turnId), deadline);
            } catch (RoleplayUploadSpoolFactory.Rejected
                    | RoleplayAudioInspector.Unavailable rejected) {
                var reason =
                        rejected instanceof RoleplayUploadSpoolFactory.Rejected upload
                                ? switch (upload.reason()) {
                                    case EMPTY -> RoleplayTurnResult.InputFailure.EMPTY_UPLOAD;
                                    case UNSUPPORTED_FORMAT ->
                                            RoleplayTurnResult.InputFailure.UNSUPPORTED_AUDIO;
                                    case INVALID_AUDIO ->
                                            RoleplayTurnResult.InputFailure.INVALID_AUDIO;
                                    case TOO_LARGE ->
                                            RoleplayTurnResult.InputFailure.UPLOAD_TOO_LARGE;
                                    case TOO_LONG -> RoleplayTurnResult.InputFailure.AUDIO_TOO_LONG;
                                }
                                : RoleplayTurnResult.InputFailure.AUDIO_INSPECTION_UNAVAILABLE;
                var fingerprint =
                        rejected instanceof RoleplayUploadSpoolFactory.Rejected upload
                                ? upload.inputFingerprint()
                                : ((RoleplayAudioInspector.Unavailable) rejected)
                                        .inputFingerprint();
                log.warn(
                        "음성 입력 재입력 안내: reason={}, cleanup_failed={}",
                        reason,
                        rejected.getSuppressed().length > 0);
                if (reason == RoleplayTurnResult.InputFailure.UPLOAD_TOO_LARGE
                        && fingerprint == null
                        && unidentifiedUploads != null) {
                    return unidentifiedUploads.recordOwned(scope, idempotencyKey, deadline);
                }
                if (fingerprint != null) {
                    return responses.map(
                            turns.executeUploadFailure(
                                    scope,
                                    requestId,
                                    turnId,
                                    idempotencyKey,
                                    fingerprint,
                                    reason,
                                    deadline));
                }
                boolean textRecommended =
                        recoveries.textRecommended(
                                new RoleplayVoiceRecoverySource.Key(
                                        scope.childId(),
                                        scope.activityId(),
                                        scope.sessionId(),
                                        scope.scenarioId(),
                                        scope.scenarioVersion()),
                                deadline);
                deadline.requireActive();
                return responses.map(
                        new RoleplayCheckpointedTurnExecutor.InputRecovery(
                                outcomes.inputRecovery(), textRecommended, false));
            }
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
