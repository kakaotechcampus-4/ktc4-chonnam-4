package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.Kind;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome.Action;
import java.util.Objects;

/**
 * Maps terminal results without another AI call. Rejected candidates never enter recovery output.
 */
public final class RoleplayTurnOutcomeResolver {
    private final RoleplayNoticeCatalog notices;

    public RoleplayTurnOutcomeResolver(RoleplayNoticeCatalog notices) {
        this.notices = Objects.requireNonNull(notices);
    }

    /** 공통 재입력 안내를 만들며 실패 집계와 학습 판정은 호출 경계에서 처리한다. */
    public RoleplayTurnOutcome.Recovery inputRecovery() {
        return new RoleplayTurnOutcome.InputRetry(notices.find(Kind.INPUT_RECOVERY).orElseThrow());
    }

    public RoleplayTurnOutcome resolve(RoleplayTurnResult result) {
        Objects.requireNonNull(result);
        return switch (result) {
            case RoleplayTurnResult.SpokenReady spoken ->
                    new RoleplayTurnOutcome.SpokenResponse(
                            approved(spoken.ready()), spoken.canonicalUtterance(), spoken.speech());
            case RoleplayTurnResult.Ready ready -> approved(ready);
            case RoleplayTurnResult.InputRejected rejected -> inputRetry(rejected);
            case RoleplayTurnResult.InputUnsafe ignored -> safetyStop();
            case RoleplayTurnResult.InputCheckUnavailable ignored ->
                    new RoleplayTurnOutcome.InputCheckRetry(
                            notices.find(Kind.SAFETY_CHECK_ERROR)
                                    .orElseGet(
                                            () -> notices.find(Kind.INPUT_RECOVERY).orElseThrow()));
            case RoleplayTurnResult.TimedOut ignored ->
                    guidance(Kind.TIMEOUT, Action.REQUEST_NEW_INPUT);
            case RoleplayTurnResult.RecoveryRequired recovery -> recover(recovery);
            case RoleplayTurnResult.CallFailed ignored ->
                    throw new IllegalArgumentException(
                            "Resolve only terminal, retry-coordinated results");
            case RoleplayTurnResult.Rejected ignored ->
                    throw new IllegalArgumentException(
                            "Resolve only terminal, retry-coordinated results");
        };
    }

    private RoleplayTurnOutcome.ApprovedResponse approved(RoleplayTurnResult.Ready ready) {
        return new RoleplayTurnOutcome.ApprovedResponse(
                ready.candidate().candidateId(), ready.candidate().text());
    }

    private RoleplayTurnOutcome.Recovery inputRetry(RoleplayTurnResult.InputRejected rejected) {
        if (switch (rejected.reason()) {
            case EMPTY_UPLOAD,
                            UNSUPPORTED_AUDIO,
                            INVALID_AUDIO,
                            AUDIO_INSPECTION_UNAVAILABLE,
                            UPLOAD_TOO_LARGE,
                            AUDIO_TOO_LONG ->
                    true;
            default -> false;
        }) return inputRecovery();
        Kind kind =
                rejected.reason() == RoleplayTurnResult.InputFailure.TRANSCRIPTION_FAILED
                        ? Kind.SAFE_FALLBACK
                        : Kind.INPUT_QUALITY;
        return notices.find(kind)
                .<RoleplayTurnOutcome.Recovery>map(RoleplayTurnOutcome.InputRetry::new)
                .orElseGet(this::inputRecovery);
    }

    private RoleplayTurnOutcome.Recovery recover(RoleplayTurnResult.RecoveryRequired recovery) {
        return switch (recovery.target()) {
            case SAFETY_ESCALATION -> safetyStop();
            case INPUT_CONFIRMATION -> guidance(Kind.INPUT_CONFIRMATION, Action.REQUEST_NEW_INPUT);
            case SAFE_FALLBACK -> fallback(recovery.operation());
            case RESPONSE_GENERATION, CAUSE_ANALYSIS, RESPONSE_EVALUATION ->
                    throw new IllegalArgumentException(
                            "Unfinished retry target is not deliverable");
        };
    }

    private RoleplayTurnOutcome.Recovery fallback(AiOperation operation) {
        // DEC-027: evaluator failure is an error notice and turn retry, not a learning answer.
        return switch (operation) {
            case RESPONSE_EVALUATION -> guidance(Kind.SAFETY_CHECK_ERROR, Action.RETRY_TURN);
            case RESPONSE_GENERATION -> guidance(Kind.SAFE_FALLBACK, Action.REQUEST_NEW_INPUT);
            case INITIAL_DIFFICULTY_DECISION,
                            SCENARIO_GENERATION,
                            CAUSE_ANALYSIS,
                            NEXT_DIFFICULTY_DECISION,
                            SPEECH_TRANSCRIPTION,
                            SPEECH_SYNTHESIS ->
                    throw new IllegalArgumentException("Unexpected fallback operation");
        };
    }

    private RoleplayTurnOutcome.Recovery guidance(Kind kind, Action action) {
        return notices.find(kind)
                .<RoleplayTurnOutcome.Recovery>map(
                        notice -> new RoleplayTurnOutcome.Guidance(notice, action))
                .orElseGet(
                        () ->
                                action == Action.REQUEST_NEW_INPUT
                                        ? inputRecovery()
                                        : new RoleplayTurnOutcome.NoticeUnavailable(action));
    }

    private RoleplayTurnOutcome.Recovery safetyStop() {
        var child = notices.find(Kind.SAFETY_ESCALATION_CHILD);
        var teacher = notices.find(Kind.SAFETY_ESCALATION_TEACHER);
        if (child.isEmpty() || teacher.isEmpty()) {
            return new RoleplayTurnOutcome.NoticeUnavailable(Action.STOP_DIALOGUE);
        }
        return new RoleplayTurnOutcome.SafetyStop(child.get(), teacher.get());
    }
}
