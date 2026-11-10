package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.observation.RoleplayTiming;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.Kind;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome.Action;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        var outcome = resolved(result);
        RoleplayTiming.note("DECISION", resolution(result, outcome));
        return outcome;
    }

    /** 턴이 왜 이 결과로 끝났는지 실행 기록에 남길 고정 코드. error 는 설계된 안내(입력 품질·안전 판정)가 아니라 호출 실패·한도·시간 초과로 끝난 경우다. */
    static Map<String, Object> resolution(RoleplayTurnResult result, RoleplayTurnOutcome outcome) {
        String verdict;
        boolean error = false;
        List<String> codes = List.of();
        Object operation = null;
        switch (result) {
            case RoleplayTurnResult.SpokenReady ignored -> verdict = "APPROVED";
            case RoleplayTurnResult.Ready ignored -> verdict = "APPROVED";
            case RoleplayTurnResult.InputRejected rejected -> {
                verdict = "INPUT_REJECTED";
                codes = List.of(rejected.reason().name());
                error =
                        rejected.reason() == RoleplayTurnResult.InputFailure.TRANSCRIPTION_FAILED
                                || rejected.reason()
                                        == RoleplayTurnResult.InputFailure
                                                .AUDIO_INSPECTION_UNAVAILABLE;
            }
            case RoleplayTurnResult.InputUnsafe ignored -> verdict = "INPUT_UNSAFE";
            case RoleplayTurnResult.InputCheckUnavailable ignored -> {
                verdict = "INPUT_CHECK_UNAVAILABLE";
                error = true;
            }
            case RoleplayTurnResult.TimedOut ignored -> {
                verdict = "TIMED_OUT";
                error = true;
            }
            case RoleplayTurnResult.RecoveryRequired recovery -> {
                verdict = recovery.reason().name();
                codes = List.of(recovery.target().name());
                operation = recovery.operation();
                error =
                        recovery.reason() != RoleplayTurnResult.RecoveryReason.MODEL_REQUESTED
                                && recovery.reason()
                                        != RoleplayTurnResult.RecoveryReason.INPUT_SAFETY_REJECTED;
            }
            case RoleplayTurnResult.CallFailed ignored -> verdict = "CALL_FAILED";
            case RoleplayTurnResult.Rejected ignored -> verdict = "REJECTED";
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("step", "TURN_RESOLUTION");
        data.put("verdict", verdict);
        data.put("accepted", "APPROVED".equals(verdict));
        data.put("error", error);
        data.put("codes", codes);
        data.put("operation", operation);
        data.put("next", next(outcome));
        return data;
    }

    private static String next(RoleplayTurnOutcome outcome) {
        return switch (outcome) {
            case RoleplayTurnOutcome.SpokenResponse ignored -> "RESPONSE_DELIVERY";
            case RoleplayTurnOutcome.ApprovedResponse ignored -> "RESPONSE_DELIVERY";
            case RoleplayTurnOutcome.InputRetry retry -> "NOTICE_" + retry.notice().kind();
            case RoleplayTurnOutcome.InputCheckRetry retry -> "NOTICE_" + retry.notice().kind();
            case RoleplayTurnOutcome.Guidance guidance -> "NOTICE_" + guidance.notice().kind();
            case RoleplayTurnOutcome.SafetyStop ignored -> "SAFETY_STOP";
            default -> "NOTICE_UNAVAILABLE";
        };
    }

    private RoleplayTurnOutcome resolved(RoleplayTurnResult result) {
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
                ready.candidate().candidateId(),
                ready.candidate().text(),
                ready.candidate().mascotHint());
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
            case INPUT_VALIDATION,
                            INITIAL_DIFFICULTY_DECISION,
                            SCENARIO_GENERATION,
                            CAUSE_ANALYSIS,
                            NEXT_DIFFICULTY_DECISION,
                            REPORT_GENERATION,
                            REPORT_REVIEW,
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
