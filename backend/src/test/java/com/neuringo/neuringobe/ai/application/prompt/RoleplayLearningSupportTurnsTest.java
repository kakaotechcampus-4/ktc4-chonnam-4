package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Event;
import com.neuringo.neuringobe.ai.application.prompt.RoleplaySupportAdjustmentContext.Outcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayLearningSupportTurnsTest {
    private final UUID goal = UUID.randomUUID();

    @Test
    void firstInputHasLearningTurnOne() {
        assertThat(RoleplayLearningSupportTurns.nextTurn(goal, 1, List.of())).hasValue(1);
    }

    @Test
    void excludesUnassessableInputsAndCountsAssessableInputsOnlyForTheirGoal() {
        assertThat(
                        RoleplayLearningSupportTurns.nextTurn(
                                goal,
                                4,
                                List.of(
                                        event(goal, "NOT_ASSESSABLE"),
                                                event(UUID.randomUUID(), "HELP_NEEDED"),
                                        event(goal, "HELP_NEEDED"), event(goal, "NOT_ASSESSABLE"))))
                .hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GOAL_ACHIEVED",
                "CORRECT_BUT_SHALLOW",
                "PARTIAL_UNDERSTANDING",
                "MISCONCEPTION",
                "OFF_TOPIC",
                "HELP_NEEDED"
            })
    void knownLearningStatesCountWithoutRequiringAnIndependenceQualification(String state) {
        assertThat(RoleplayLearningSupportTurns.nextTurn(goal, 2, List.of(event(goal, state))))
                .hasValue(2);
    }

    @Test
    void missingOrUnclassifiedHistoryDoesNotInventACount() {
        assertThat(RoleplayLearningSupportTurns.nextTurn(goal, 2, List.of())).isEmpty();
        assertThat(RoleplayLearningSupportTurns.nextTurn(goal, 2, List.of(event(goal, null))))
                .isEmpty();
        assertThat(RoleplayLearningSupportTurns.nextTurn(goal, 2, List.of(event(goal, "INVALID"))))
                .isEmpty();
        assertThat(
                        RoleplayLearningSupportTurns.nextTurn(
                                goal, 1, List.of(event(goal, "HELP_NEEDED"))))
                .isEmpty();
    }

    @Test
    void duplicateInputIsRejectedInsteadOfSpendingAnExtraLearningTurn() {
        var event = event(goal, "HELP_NEEDED");
        assertThatThrownBy(
                        () -> RoleplayLearningSupportTurns.nextTurn(goal, 3, List.of(event, event)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionCounterSpansGoalsButExcludesUnassessableInputs() {
        var history =
                List.of(
                        sessionEvent(1, goal, "NOT_ASSESSABLE"),
                        sessionEvent(2, UUID.randomUUID(), "HELP_NEEDED"),
                        sessionEvent(3, goal, "GOAL_ACHIEVED"));
        assertThat(RoleplayLearningSupportTurns.nextSessionTurn(4, history)).hasValue(3);
        assertThat(RoleplayLearningSupportTurns.nextSessionTurn(1, List.of())).hasValue(1);
    }

    @Test
    void sessionCounterRequiresCompleteOrderedClassifiedHistory() {
        assertThat(RoleplayLearningSupportTurns.nextSessionTurn(2, List.of())).isEmpty();
        assertThat(
                        RoleplayLearningSupportTurns.nextSessionTurn(
                                2, List.of(sessionEvent(1, goal, null))))
                .isEmpty();
        assertThat(
                        RoleplayLearningSupportTurns.nextSessionTurn(
                                2, List.of(sessionEvent(2, goal, "HELP_NEEDED"))))
                .isEmpty();
        assertThat(
                        RoleplayLearningSupportTurns.nextSessionTurn(
                                2, List.of(sessionEvent(1, goal, "INVALID"))))
                .isEmpty();
    }

    private Event sessionEvent(int number, UUID goalId, String state) {
        return new Event(
                UUID.randomUUID(), goalId, number, number, Outcome.UNKNOWN, state, List.of());
    }

    private Event event(UUID goalId, String state) {
        return new Event(UUID.randomUUID(), goalId, 1, 1, Outcome.UNKNOWN, state, List.of());
    }
}
