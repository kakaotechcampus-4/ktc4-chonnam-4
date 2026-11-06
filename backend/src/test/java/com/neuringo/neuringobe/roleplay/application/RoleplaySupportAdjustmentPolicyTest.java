package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.*;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplaySupportAdjustmentPolicyTest {
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    private final RoleplaySupportAdjustmentPolicy.Rules reset =
            new RoleplaySupportAdjustmentPolicy.Rules(
                    2,
                    RoleplaySupportAdjustmentPolicy.InProgressHandling.RESET_SEQUENCE,
                    "synthetic-support-v1");

    @Test
    void absentPolicyDoesNotInventACountOrEligibility() {
        var result =
                RoleplaySupportAdjustmentPolicy.evaluate(
                        List.of(event(1, first, Outcome.ACHIEVED_WITHOUT_HINT)), null);
        assertThat(result.status()).isEqualTo(Status.POLICY_REQUIRED);
        assertThat(result.independentGoalCount()).isNull();
        assertThat(result.requiredIndependentGoals()).isNull();
        assertThat(result.confirmedInputs()).hasSize(1);
    }

    @Test
    void twoDifferentGoalsMeetOnlyTheExplicitSyntheticThreshold() {
        var events =
                List.of(
                        event(1, first, Outcome.ACHIEVED_WITHOUT_HINT),
                        event(2, second, Outcome.ACHIEVED_WITHOUT_HINT));
        var result = RoleplaySupportAdjustmentPolicy.evaluate(events, reset);
        assertThat(result.status()).isEqualTo(Status.ELIGIBLE_NEXT_GOAL);
        assertThat(result.independentGoalCount()).isEqualTo(2);
        assertThat(result.basis()).containsExactly(events.getLast(), events.getFirst());
        assertThat(
                        RoleplaySupportAdjustmentPolicy.evaluate(
                                        events,
                                        new RoleplaySupportAdjustmentPolicy.Rules(
                                                3,
                                                reset.inProgressHandling(),
                                                reset.policyVersion()))
                                .status())
                .isEqualTo(Status.NOT_ELIGIBLE);
    }

    @Test
    void repeatedGoalAndMultipleCriteriaAreNotMultipleGoalAchievements() {
        var result =
                RoleplaySupportAdjustmentPolicy.evaluate(
                        List.of(
                                event(1, first, Outcome.ACHIEVED_WITHOUT_HINT),
                                event(2, first, Outcome.ACHIEVED_WITHOUT_HINT)),
                        reset);
        assertThat(result.status()).isEqualTo(Status.NOT_ELIGIBLE);
        assertThat(result.independentGoalCount()).isEqualTo(1);
        assertThat(result.basis().getFirst().basis()).hasSize(2);
    }

    @Test
    void notAssessableNeitherAddsAchievementNorBreaksTheSequence() {
        var result =
                RoleplaySupportAdjustmentPolicy.evaluate(
                        List.of(
                                event(1, first, Outcome.ACHIEVED_WITHOUT_HINT),
                                event(2, second, Outcome.NOT_ASSESSABLE),
                                event(3, second, Outcome.ACHIEVED_WITHOUT_HINT)),
                        reset);
        assertThat(result.status()).isEqualTo(Status.ELIGIBLE_NEXT_GOAL);
        assertThat(result.independentGoalCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(
            value = Outcome.class,
            names = {"ACHIEVED_WITH_SUPPORT", "SUPPORTED_NOT_ACHIEVED"})
    void assistedResultsCannotContinueAHintFreeSequence(Outcome interruption) {
        var result =
                RoleplaySupportAdjustmentPolicy.evaluate(
                        List.of(
                                event(1, first, Outcome.ACHIEVED_WITHOUT_HINT),
                                event(2, second, interruption),
                                event(3, second, Outcome.ACHIEVED_WITHOUT_HINT)),
                        reset);
        assertThat(result.status()).isEqualTo(Status.NOT_ELIGIBLE);
        assertThat(result.independentGoalCount()).isEqualTo(1);
    }

    @Test
    void inProgressInterruptionUsesOnlyTheExplicitHandlingRule() {
        var events =
                List.of(
                        event(1, first, Outcome.ACHIEVED_WITHOUT_HINT),
                        event(2, second, Outcome.IN_PROGRESS),
                        event(3, second, Outcome.ACHIEVED_WITHOUT_HINT));
        assertThat(RoleplaySupportAdjustmentPolicy.evaluate(events, reset).status())
                .isEqualTo(Status.NOT_ELIGIBLE);
        assertThat(
                        RoleplaySupportAdjustmentPolicy.evaluate(
                                        events,
                                        new RoleplaySupportAdjustmentPolicy.Rules(
                                                2,
                                                RoleplaySupportAdjustmentPolicy.InProgressHandling
                                                        .KEEP_SEQUENCE,
                                                "synthetic-keep-v1"))
                                .status())
                .isEqualTo(Status.ELIGIBLE_NEXT_GOAL);
    }

    @Test
    void unknownPastEvidenceIsNotInventedAsSuccessOrFailure() {
        var events =
                List.of(
                        event(1, first, Outcome.UNKNOWN),
                        event(2, second, Outcome.ACHIEVED_WITHOUT_HINT));
        assertThat(RoleplaySupportAdjustmentPolicy.evaluate(events, reset).status())
                .isEqualTo(Status.EVIDENCE_UNAVAILABLE);
        assertThat(
                        RoleplaySupportAdjustmentPolicy.evaluate(
                                        List.of(
                                                event(1, UUID.randomUUID(), Outcome.UNKNOWN),
                                                event(2, first, Outcome.ACHIEVED_WITHOUT_HINT),
                                                event(3, second, Outcome.ACHIEVED_WITHOUT_HINT)),
                                        reset)
                                .status())
                .isEqualTo(Status.ELIGIBLE_NEXT_GOAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "order"})
    void duplicateOrUnorderedInputsAreRejected(String change) {
        var one = event(1, first, Outcome.ACHIEVED_WITHOUT_HINT);
        var two = event(2, second, Outcome.ACHIEVED_WITHOUT_HINT);
        assertThatThrownBy(
                        () ->
                                RoleplaySupportAdjustmentPolicy.evaluate(
                                        change.equals("duplicate")
                                                ? List.of(one, one)
                                                : List.of(two, one),
                                        reset))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Event event(int turnNumber, UUID goal, Outcome outcome) {
        UUID turn = UUID.randomUUID();
        return new Event(
                turn,
                goal,
                turnNumber,
                turnNumber,
                outcome,
                outcome == Outcome.UNKNOWN
                        ? null
                        : outcome == Outcome.NOT_ASSESSABLE ? "NOT_ASSESSABLE" : "GOAL_ACHIEVED",
                outcome == Outcome.ACHIEVED_WITHOUT_HINT || outcome == Outcome.ACHIEVED_WITH_SUPPORT
                        ? List.of(
                                new Reference(turn, goal, "emotion-proof"),
                                new Reference(turn, goal, "cause-proof"))
                        : List.of());
    }
}
