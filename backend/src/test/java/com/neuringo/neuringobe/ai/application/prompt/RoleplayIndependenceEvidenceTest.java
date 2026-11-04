package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayIndependenceEvidenceTest {
    private final UUID goal = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Reference reference = new Reference(UUID.randomUUID(), goal, "proof");

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"EXPLICIT", "IMPLICIT"})
    void firstS0QuestionIsOnlyAPositiveCandidateForIndependentReview(Status status) {
        assertThat(review(status, goal, "S0", false, exposure(null, null, false)).eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITHOUT_RECORDED_HELP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"S1", "S2", "S3"})
    void inputQuestionCluesAndChoicesCannotBecomeHintFreeEvidence(String level) {
        assertThat(
                        review(Status.EXPLICIT, goal, level, false, exposure(null, null, false))
                                .eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITH_HELP);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"NOT_EXPRESSED", "MISUNDERSTOOD", "CONTRADICTORY"})
    void noSupportDoesNotMakeANonPositiveObservationAnAchievement(Status status) {
        assertThat(review(status, goal, "S0", false, exposure(null, null, false)).eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.NON_POSITIVE_OBSERVATION);
    }

    @Test
    void answerRevealInInputStateCannotBecomeAnIndependentCandidate() {
        assertThat(
                        review(Status.EXPLICIT, goal, "S0", true, exposure(null, null, false))
                                .eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.ANSWER_ALREADY_REVEALED);
    }

    @Test
    void observedGoalsAnswerRevealIsUsedEvenWhenTheInputQuestionTargetsAnotherGoal() {
        assertThat(
                        review(Status.EXPLICIT, other, "S0", false, exposure("S3", "S3", true))
                                .eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.ANSWER_ALREADY_REVEALED);
    }

    @Test
    void missingOtherGoalsSupportDoesNotCopyTheS0InputQuestion() {
        assertThat(
                        review(Status.EXPLICIT, other, "S0", false, exposure(null, null, false))
                                .eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.SUPPORT_UNKNOWN);
    }

    @Test
    void aDifferentGoalsOwnS0HistoryDoesNotCopyTheS2InputQuestion() {
        assertThat(
                        review(Status.IMPLICIT, other, "S2", false, exposure("S0", "S0", false))
                                .eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITHOUT_RECORDED_HELP);
    }

    @Test
    void anEarlierClueCannotDisappearWhenTheLatestQuestionUsesS0() {
        assertThat(
                        review(Status.EXPLICIT, other, "S0", false, exposure("S0", "S2", false))
                                .eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITH_HELP);
    }

    @Test
    void openingQuestionSupportRemainsKnownWithoutInventingAPriorResponse() {
        var exposure = exposure(null, "S1", false);
        assertThat(exposure.lastResponse()).isNull();
        assertThat(review(Status.EXPLICIT, other, "S0", false, exposure).eligibility())
                .isEqualTo(RoleplayIndependenceEvidence.Eligibility.POSITIVE_WITH_HELP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "lower", "invalid"})
    void missingOrConflictingMaximumSupportIsRejected(String change) {
        assertThatThrownBy(
                        () ->
                                exposure(
                                        "S2",
                                        switch (change) {
                                            case "missing" -> null;
                                            case "lower" -> "S1";
                                            default -> "S9";
                                        },
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RoleplayIndependenceEvidence.Review review(
            Status status,
            UUID inputGoal,
            String inputSupport,
            boolean inputReveal,
            GoalObservationInput.GoalExposure exposure) {
        var contexts =
                List.of(
                        new GoalObservationInput.ObservationContext(
                                reference,
                                UUID.randomUUID(),
                                2,
                                2,
                                new RoleplayInputContext(
                                        inputGoal, inputSupport, 1, 0, inputReveal),
                                exposure));
        var criteria =
                List.of(
                        new GoalObservationInput.Criterion(goal, "proof", "감정 설명"),
                        new GoalObservationInput.Criterion(other, "other-proof", "사건 설명"));
        var observations =
                new GoalObservationInput(
                        criteria,
                        List.of(new Observation(reference, status)),
                        contexts,
                        criteria.stream()
                                .map(
                                        c ->
                                                new GoalObservationInput.CurrentGoalExposure(
                                                        c.microGoalId(),
                                                        new GoalObservationInput.GoalExposure(
                                                                0, false, null, null)))
                                .toList());
        var result = RoleplayIndependenceEvidence.confirmed(observations);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().reference()).isEqualTo(reference);
        assertThat(observations.confirmedObservationContexts()).isEqualTo(contexts);
        return result.getFirst();
    }

    private GoalObservationInput.GoalExposure exposure(
            String lastLevel, String highest, boolean revealed) {
        var prior =
                lastLevel == null
                        ? null
                        : new GoalObservationInput.PriorResponse(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                goal,
                                1,
                                1,
                                lastLevel,
                                revealed ? "ANSWER_REVEAL" : "GUIDING_QUESTION");
        return new GoalObservationInput.GoalExposure(
                prior == null || revealed ? 0 : 1, revealed, prior, highest);
    }
}
