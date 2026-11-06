package com.neuringo.neuringobe.activity.domain;

import java.util.Objects;

/** 현재 학습 단계의 저장 근거만 판정하며 상태 전환이나 복구를 수행하지 않는다. */
public final class ActivityReadinessPolicy {
    private ActivityReadinessPolicy() {}

    public static ActivityReadiness evaluate(Evidence evidence) {
        Objects.requireNonNull(evidence);
        if (evidence.status() == ActivityStatus.COMPLETED) {
            return ActivityReadiness.available(ActivityNextAction.NONE);
        }
        return switch (evidence.type()) {
            case SELF_EMOTION_QUIZ, OTHER_EMOTION_QUIZ -> independentQuiz(evidence);
            case FIRST_COMBINED -> firstCombined(evidence);
            case ROLEPLAY -> roleplay(evidence);
        };
    }

    private static ActivityReadiness independentQuiz(Evidence evidence) {
        return evidence.quiz() == QuizState.INCOMPLETE
                ? ActivityReadiness.available(ActivityNextAction.QUIZ)
                : ActivityReadiness.blocked(ActivityBlockedReason.QUIZ_NOT_READY);
    }

    private static ActivityReadiness firstCombined(Evidence evidence) {
        // 기존 역할극의 재개 여부를 이전 퀴즈 자료의 존재만으로 되돌리지 않는다.
        if (evidence.session() != SessionState.ABSENT) return roleplay(evidence);
        if (evidence.quiz() == QuizState.INCOMPLETE) {
            return ActivityReadiness.available(ActivityNextAction.QUIZ);
        }
        if (evidence.quiz() != QuizState.COMPLETED) {
            return ActivityReadiness.blocked(ActivityBlockedReason.QUIZ_NOT_READY);
        }
        return roleplay(evidence);
    }

    private static ActivityReadiness roleplay(Evidence evidence) {
        if (evidence.session() == SessionState.AMBIGUOUS) {
            return ActivityReadiness.blocked(ActivityBlockedReason.RESUME_DATA_NOT_READY);
        }
        if (evidence.session() != SessionState.ABSENT) {
            if (evidence.scenario() != ScenarioState.READY) {
                return ActivityReadiness.blocked(ActivityBlockedReason.SCENARIO_NOT_READY);
            }
            return evidence.session() == SessionState.RESUMABLE
                    ? ActivityReadiness.available(ActivityNextAction.ROLEPLAY)
                    : ActivityReadiness.blocked(ActivityBlockedReason.RESUME_DATA_NOT_READY);
        }
        if (!evidence.difficultyReady()) {
            return ActivityReadiness.blocked(ActivityBlockedReason.DIFFICULTY_NOT_READY);
        }
        return switch (evidence.scenario()) {
            case UNASSIGNED -> ActivityReadiness.available(ActivityNextAction.WAIT_FOR_SCENARIO);
            case READY -> ActivityReadiness.available(ActivityNextAction.ROLEPLAY);
            case INVALID -> ActivityReadiness.blocked(ActivityBlockedReason.SCENARIO_NOT_READY);
        };
    }

    /** 권한 확인 후 저장 근거를 전달받으며 외부 요청에서 직접 구성하지 않는다. */
    public record Evidence(
            ActivityType type,
            ActivityStatus status,
            QuizState quiz,
            boolean difficultyReady,
            ScenarioState scenario,
            SessionState session) {
        public Evidence {
            Objects.requireNonNull(type);
            Objects.requireNonNull(status);
            Objects.requireNonNull(quiz);
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(session);
        }
    }

    public enum QuizState {
        // 배정이 유효하고 아직 확정되지 않은 문항이 남아 있다.
        INCOMPLETE,
        COMPLETED,
        NOT_REQUIRED,
        INVALID
    }

    public enum ScenarioState {
        UNASSIGNED,
        READY,
        INVALID
    }

    public enum SessionState {
        ABSENT,
        // 세션 식별·현재 진행·복원 자료 검증을 통과했다.
        RESUMABLE,
        INVALID,
        AMBIGUOUS
    }
}
