package com.neuringo.neuringobe.activity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.Evidence;
import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.QuizState;
import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.ScenarioState;
import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy.SessionState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ActivityReadinessPolicyTest {
    @ParameterizedTest(name = "{index}: {0}/{1}, quiz={2}, scenario={4}, session={5} -> {6}/{7}")
    @CsvSource(
            nullValues = "-",
            value = {
                "FIRST_COMBINED, NOT_STARTED, INCOMPLETE, false, INVALID, ABSENT, QUIZ, -",
                "FIRST_COMBINED, IN_PROGRESS, INCOMPLETE, false, INVALID, ABSENT, QUIZ, -",
                "FIRST_COMBINED, PAUSED, INCOMPLETE, false, INVALID, ABSENT, QUIZ, -",
                "FIRST_COMBINED, RECOVERY_NEEDED, INCOMPLETE, false, INVALID, ABSENT, QUIZ, -",
                "SELF_EMOTION_QUIZ, IN_PROGRESS, INCOMPLETE, false, INVALID, INVALID, QUIZ, -",
                "OTHER_EMOTION_QUIZ, PAUSED, INCOMPLETE, false, INVALID, INVALID, QUIZ, -",
                "FIRST_COMBINED, IN_PROGRESS, INVALID, false, INVALID, ABSENT, BLOCKED, QUIZ_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, NOT_REQUIRED, false, UNASSIGNED, ABSENT, BLOCKED, QUIZ_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, COMPLETED, false, INVALID, ABSENT, BLOCKED, DIFFICULTY_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, COMPLETED, false, UNASSIGNED, ABSENT, BLOCKED, DIFFICULTY_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, COMPLETED, true, UNASSIGNED, ABSENT, WAIT_FOR_SCENARIO, -",
                "FIRST_COMBINED, IN_PROGRESS, COMPLETED, true, READY, ABSENT, ROLEPLAY, -",
                "FIRST_COMBINED, IN_PROGRESS, COMPLETED, true, INVALID, ABSENT, BLOCKED, SCENARIO_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, INVALID, false, READY, RESUMABLE, ROLEPLAY, -",
                "FIRST_COMBINED, PAUSED, INVALID, false, READY, RESUMABLE, ROLEPLAY, -",
                "FIRST_COMBINED, RECOVERY_NEEDED, INVALID, false, READY, RESUMABLE, ROLEPLAY, -",
                "FIRST_COMBINED, IN_PROGRESS, INVALID, false, INVALID, AMBIGUOUS, BLOCKED, RESUME_DATA_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, COMPLETED, true, READY, INVALID, BLOCKED, RESUME_DATA_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, INVALID, false, INVALID, INVALID, BLOCKED, SCENARIO_NOT_READY",
                "FIRST_COMBINED, IN_PROGRESS, INVALID, false, UNASSIGNED, RESUMABLE, BLOCKED, SCENARIO_NOT_READY",
                "SELF_EMOTION_QUIZ, IN_PROGRESS, COMPLETED, false, UNASSIGNED, ABSENT, BLOCKED, QUIZ_NOT_READY",
                "OTHER_EMOTION_QUIZ, IN_PROGRESS, INVALID, false, UNASSIGNED, ABSENT, BLOCKED, QUIZ_NOT_READY",
                "ROLEPLAY, NOT_STARTED, NOT_REQUIRED, true, READY, ABSENT, ROLEPLAY, -"
            })
    void derivesOnlyTheActionAndFirstFailureForTheCurrentStage(
            ActivityType type,
            ActivityStatus status,
            QuizState quiz,
            boolean difficultyReady,
            ScenarioState scenario,
            SessionState session,
            ActivityNextAction action,
            ActivityBlockedReason reason) {
        var evidence = new Evidence(type, status, quiz, difficultyReady, scenario, session);

        assertThat(ActivityReadinessPolicy.evaluate(evidence))
                .isEqualTo(new ActivityReadiness(action, reason));
    }

    @ParameterizedTest
    @EnumSource(ActivityType.class)
    void completedActivityDoesNotReopenBecauseHistoricalEvidenceIsMissing(ActivityType type) {
        var evidence =
                new Evidence(
                        type,
                        ActivityStatus.COMPLETED,
                        QuizState.INVALID,
                        false,
                        ScenarioState.INVALID,
                        SessionState.AMBIGUOUS);

        assertThat(ActivityReadinessPolicy.evaluate(evidence))
                .isEqualTo(ActivityReadiness.available(ActivityNextAction.NONE));
    }

    @ParameterizedTest
    @EnumSource(ActivityNextAction.class)
    void blockedReasonExistsExactlyWhenActionIsBlocked(ActivityNextAction action) {
        if (action == ActivityNextAction.BLOCKED) {
            assertThatThrownBy(() -> new ActivityReadiness(action, null))
                    .isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThatThrownBy(
                            () ->
                                    new ActivityReadiness(
                                            action, ActivityBlockedReason.QUIZ_NOT_READY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
