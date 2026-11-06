package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceFailureProgress.CountingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayVoiceFailureProgressTest {
    @Test
    void startsWithoutFailuresOrTextRecommendation() {
        var progress = RoleplayVoiceFailureProgress.initial();

        assertThat(progress.failureCount()).isZero();
        assertThat(progress.textRecommended()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = InputFailure.class,
            names = {
                "LOW_CONFIDENCE",
                "NO_SPEECH",
                "TRANSCRIPTION_FAILED",
                "EMPTY_UPLOAD",
                "UNSUPPORTED_AUDIO",
                "INVALID_AUDIO",
                "AUDIO_INSPECTION_UNAVAILABLE",
                "UPLOAD_TOO_LARGE",
                "AUDIO_TOO_LONG",
                "UNKNOWN_CONFIDENCE",
                "INVALID_INPUT"
            })
    void eachConfirmedNewInputFailureCountsOnce(InputFailure reason) {
        var previous = RoleplayVoiceFailureProgress.initial();

        var update = previous.recordNewFailure(reason);

        assertThat(update.status()).isEqualTo(CountingStatus.COUNTED);
        assertThat(update.progress().failureCount()).isEqualTo(1);
        assertThat(update.progress().textRecommended()).isFalse();
        assertThat(previous.failureCount()).isZero();
    }

    @Test
    void combinesDifferentFailureReasonsAndRecommendsTextOnThirdFailure() {
        var first =
                RoleplayVoiceFailureProgress.initial()
                        .recordNewFailure(InputFailure.LOW_CONFIDENCE)
                        .progress();
        var second = first.recordNewFailure(InputFailure.NO_SPEECH).progress();
        var third = second.recordNewFailure(InputFailure.TRANSCRIPTION_FAILED).progress();

        assertThat(first.failureCount()).isEqualTo(1);
        assertThat(first.textRecommended()).isFalse();
        assertThat(second.failureCount()).isEqualTo(2);
        assertThat(second.textRecommended()).isFalse();
        assertThat(third.failureCount()).isEqualTo(3);
        assertThat(third.textRecommended()).isTrue();
    }

    @Test
    void furtherVoiceFailuresRemainCountableAfterTextRecommendation() {
        var progress = new RoleplayVoiceFailureProgress(3);

        var update = progress.recordNewFailure(InputFailure.NO_SPEECH);

        assertThat(update.status()).isEqualTo(CountingStatus.COUNTED);
        assertThat(update.progress().failureCount()).isEqualTo(4);
        assertThat(update.progress().textRecommended()).isTrue();
    }

    @Test
    void restoresRecommendationFromPreviouslyRecordedFailureCount() {
        var restored = new RoleplayVoiceFailureProgress(2);

        var update = restored.recordNewFailure(InputFailure.LOW_CONFIDENCE);

        assertThat(restored.textRecommended()).isFalse();
        assertThat(update.progress().failureCount()).isEqualTo(3);
        assertThat(update.progress().textRecommended()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = InputFailure.class,
            names = {"INVALID_INPUT"})
    void countedUnclearInputPreservesExistingRecommendation(InputFailure reason) {
        for (int previousCount : new int[] {0, 2, 3, 4}) {
            var previous = new RoleplayVoiceFailureProgress(previousCount);

            var update = previous.recordNewFailure(reason);

            assertThat(update.status()).isEqualTo(CountingStatus.COUNTED);
            assertThat(update.progress().failureCount()).isEqualTo(previousCount + 1);
            assertThat(update.progress().textRecommended()).isEqualTo(previousCount + 1 >= 3);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void rejectsInvalidStoredFailureCounts(int count) {
        assertThatThrownBy(() -> new RoleplayVoiceFailureProgress(count))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingReasonWithoutChangingProgress() {
        var progress = new RoleplayVoiceFailureProgress(2);

        assertThatThrownBy(() -> progress.recordNewFailure(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(progress.failureCount()).isEqualTo(2);
        assertThat(progress.textRecommended()).isFalse();
    }

    @Test
    void failsInsteadOfOverflowingStoredCount() {
        var progress = new RoleplayVoiceFailureProgress(Integer.MAX_VALUE);

        assertThatThrownBy(() -> progress.recordNewFailure(InputFailure.LOW_CONFIDENCE))
                .isInstanceOf(ArithmeticException.class);
        assertThat(progress.textRecommended()).isTrue();
    }
}
