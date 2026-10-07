package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.Kind;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome.Action;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.Objects;

/**
 * Maps terminal results without another AI call. Rejected candidates never enter recovery output.
 */
public final class RoleplayTurnOutcomeResolver {
    private final RoleplayNoticeCatalog notices;

    public RoleplayTurnOutcomeResolver(RoleplayNoticeCatalog notices) {
        this.notices = Objects.requireNonNull(notices);
    }

    public RoleplayTurnOutcome resolve(RoleplayTurnResult result) {
        Objects.requireNonNull(result);
        if (result instanceof RoleplayTurnResult.SpokenReady spoken) {
            var response = (RoleplayTurnOutcome.ApprovedResponse) resolve(spoken.ready());
            return new RoleplayTurnOutcome.SpokenResponse(
                    response, spoken.canonicalUtterance(), spoken.speech());
        }
        if (result instanceof RoleplayTurnResult.InputRejected rejected) {
            Kind kind =
                    rejected.reason() == RoleplayTurnResult.InputFailure.TRANSCRIPTION_FAILED
                            ? Kind.SAFE_FALLBACK
                            : Kind.INPUT_QUALITY;
            return notices.find(kind)
                    .<RoleplayTurnOutcome>map(RoleplayTurnOutcome.InputRetry::new)
                    .orElseGet(RoleplayTurnOutcome.InputNoticeUnavailable::new);
        }
        if (result instanceof RoleplayTurnResult.Ready ready) {
            return new RoleplayTurnOutcome.ApprovedResponse(
                    ready.candidate().candidateId(), ready.candidate().text());
        }
        if (result instanceof RoleplayTurnResult.TimedOut) {
            return guidance(Kind.TIMEOUT, Action.REQUEST_NEW_INPUT);
        }
        if (!(result instanceof RoleplayTurnResult.RecoveryRequired recovery)) {
            throw new IllegalArgumentException("Resolve only terminal, retry-coordinated results");
        }
        if (recovery.target() == RetryTarget.SAFETY_ESCALATION) return safetyStop();
        if (recovery.target() == RetryTarget.INPUT_CONFIRMATION) {
            return guidance(Kind.INPUT_CONFIRMATION, Action.REQUEST_NEW_INPUT);
        }
        if (recovery.target() != RetryTarget.SAFE_FALLBACK) {
            throw new IllegalArgumentException("Unfinished retry target is not deliverable");
        }
        // DEC-027: evaluator failure is an error notice and turn retry, not a learning answer.
        if (recovery.operation() == AiOperation.RESPONSE_EVALUATION) {
            return guidance(Kind.SAFETY_CHECK_ERROR, Action.RETRY_TURN);
        }
        if (recovery.operation() != AiOperation.RESPONSE_GENERATION) {
            throw new IllegalArgumentException("Unexpected fallback operation");
        }
        return guidance(Kind.SAFE_FALLBACK, Action.REQUEST_NEW_INPUT);
    }

    private RoleplayTurnOutcome guidance(Kind kind, Action action) {
        return notices.find(kind)
                .<RoleplayTurnOutcome>map(
                        notice -> new RoleplayTurnOutcome.Guidance(notice, action))
                .orElseGet(() -> new RoleplayTurnOutcome.NoticeUnavailable(action));
    }

    private RoleplayTurnOutcome safetyStop() {
        var child = notices.find(Kind.SAFETY_ESCALATION_CHILD);
        var teacher = notices.find(Kind.SAFETY_ESCALATION_TEACHER);
        if (child.isEmpty() || teacher.isEmpty()) {
            return new RoleplayTurnOutcome.NoticeUnavailable(Action.STOP_DIALOGUE);
        }
        return new RoleplayTurnOutcome.SafetyStop(child.get(), teacher.get());
    }
}
