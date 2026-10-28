package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayGoalInputHistoryTest {
    private final UUID event = UUID.randomUUID();
    private final UUID emotion = UUID.randomUUID();

    @Test
    void countsInputFocusAcrossGoalRevisitsWithoutCountingTheNextQuestion() {
        var history =
                RoleplayGoalInputHistory.restore(
                        Set.of(event, emotion),
                        List.of(
                                response(1, emotion, input(event, 1)),
                                response(2, event, input(emotion, 1)),
                                response(3, emotion, input(event, 2))));
        assertThat(history.exactAttemptCount(event)).hasValue(2);
        assertThat(history.nextSupportTurn(event)).hasValue(3);
        assertThat(history.exactAttemptCount(emotion)).hasValue(1);
        assertThat(history.nextSupportTurn(emotion)).hasValue(2);
        assertThat(history.byGoal().get(event).knownAttempts())
                .extracting(a -> a.turnNumber())
                .containsExactly(1, 3);
    }

    @Test
    void noConfirmedInputStartsAtOneRegardlessOfGoalOrder() {
        var history = RoleplayGoalInputHistory.restore(Set.of(emotion, event), List.of());
        assertThat(history.complete()).isTrue();
        assertThat(history.exactAttemptCount(event)).hasValue(0);
        assertThat(history.nextSupportTurn(event)).hasValue(1);
        assertThat(history.nextSupportTurn(emotion)).hasValue(1);
    }

    @Test
    void oneUnknownInputMakesExactCountsUnknownForEveryGoal() {
        var history =
                RoleplayGoalInputHistory.restore(
                        Set.of(event, emotion),
                        List.of(response(1, event, null), response(2, event, input(emotion, 7))));
        assertThat(history.complete()).isFalse();
        assertThat(history.byGoal().get(emotion).knownAttempts()).hasSize(1);
        assertThat(history.exactAttemptCount(emotion)).isEmpty();
        assertThat(history.exactAttemptCount(event)).isEmpty();
        assertThat(history.nextSupportTurn(event)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void repeatedOrSkippedSupportTurnIsRejected(int supportTurn) {
        assertThatThrownBy(
                        () ->
                                RoleplayGoalInputHistory.restore(
                                        Set.of(event),
                                        List.of(
                                                response(1, event, input(event, 1)),
                                                response(2, event, input(event, supportTurn)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameConfirmedTurnCannotBeCountedTwice() {
        var first = response(1, event, input(event, 1));
        var duplicate =
                new RoleplayGoalResponseHistory.Response(
                        first.turnId(),
                        UUID.randomUUID(),
                        2,
                        2,
                        event,
                        "S1",
                        "GUIDING_QUESTION",
                        "PROBE_EVENT",
                        input(event, 2));
        assertThatThrownBy(
                        () ->
                                RoleplayGoalInputHistory.restore(
                                        Set.of(event), List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RoleplayInputContext input(UUID goal, int supportTurn) {
        return new RoleplayInputContext(goal, "S1", supportTurn, 0, false);
    }

    private RoleplayGoalResponseHistory.Response response(
            int turn, UUID nextFocus, RoleplayInputContext input) {
        return new RoleplayGoalResponseHistory.Response(
                UUID.randomUUID(),
                UUID.randomUUID(),
                turn,
                turn,
                nextFocus,
                "S1",
                "GUIDING_QUESTION",
                "PROBE_EVENT",
                input);
    }
}
