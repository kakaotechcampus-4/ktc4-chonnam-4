package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayContextAssemblerTest {
    private final UUID microGoal = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();
    private final UUID turn = UUID.randomUUID();
    private final Scope scope =
            new Scope(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    3,
                    7,
                    2,
                    microGoal,
                    "S1",
                    true);

    @Test
    void preparesVoiceFromTrustedScopeAndExplicitPolicyWithoutLearnerText() {
        var observed = new AtomicReference<RoleplayContextSource.Key>();
        var state = state(microGoal, "S1", 3);
        var snapshot = snapshot(key(), state, List.of());
        var prepared =
                new RoleplayContextAssembler(
                                key -> {
                                    observed.set(key);
                                    return Optional.of(snapshot);
                                })
                        .prepareVoice(scope, request, turn, RoleplayTurnDeadline.start());
        assertThat(observed.get()).isEqualTo(key());
        assertThat(prepared.trace()).isEqualTo(scope.trace(request, turn));
        assertThat(prepared.input().scenario()).isEqualTo(snapshot.scenario());
        assertThat(prepared.input().state()).isEqualTo(state);
        assertThat(prepared.input().state().maximumTurnCount()).isEqualTo(77);
        assertThat(prepared.input().state().maximumSupportTurnsPerMicroGoal()).isEqualTo(9);
        assertThat(prepared.input().learnerTurn().turnId()).isEqualTo(turn);
        assertThat(prepared.input().learnerTurn().canonicalUtterance()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "child",
                "activity",
                "goal",
                "session",
                "scenario",
                "scenarioVersion",
                "checkpoint"
            })
    void rejectsContextFromAnotherScopeOrVersion(String field) {
        var k = key();
        var wrong =
                new RoleplayContextSource.Key(
                        field.equals("child") ? UUID.randomUUID() : k.childId(),
                        field.equals("activity") ? UUID.randomUUID() : k.activityId(),
                        field.equals("goal") ? UUID.randomUUID() : k.goalId(),
                        field.equals("session") ? UUID.randomUUID() : k.sessionId(),
                        field.equals("scenario") ? UUID.randomUUID() : k.scenarioId(),
                        field.equals("scenarioVersion") ? 4 : k.scenarioVersion(),
                        field.equals("checkpoint") ? 8 : k.checkpointVersion());
        rejected(snapshot(wrong, state(microGoal, "S1", 3), List.of()));
    }

    @Test
    void missingSourceDataDoesNotFabricateContext() {
        assertThatThrownBy(
                        () ->
                                new RoleplayContextAssembler(key -> Optional.empty())
                                        .prepareVoice(
                                                scope, request, turn, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayContextAssembler.Unavailable.class);
    }

    @Test
    void rejectsInconsistentTurnGoalOrSupport() {
        rejected(snapshot(key(), state(microGoal, "S1", 2), List.of()));
        rejected(snapshot(key(), state(UUID.randomUUID(), "S1", 3), List.of()));
        rejected(snapshot(key(), state(microGoal, "S2", 3), List.of()));
    }

    @Test
    void firstTurnMayUseTrustedInitialGoalAndSupport() {
        var initial =
                new Scope(
                        scope.childId(),
                        scope.classId(),
                        scope.activityId(),
                        scope.goalId(),
                        scope.sessionId(),
                        scope.scenarioId(),
                        3,
                        0,
                        0,
                        null,
                        null,
                        true);
        var initialKey =
                new RoleplayContextSource.Key(
                        initial.childId(),
                        initial.activityId(),
                        initial.goalId(),
                        initial.sessionId(),
                        initial.scenarioId(),
                        3,
                        0);
        var prepared =
                new RoleplayContextAssembler(
                                key ->
                                        Optional.of(
                                                snapshot(
                                                        initialKey,
                                                        state(microGoal, "S1", 1),
                                                        List.of())))
                        .prepareVoice(initial, request, turn, RoleplayTurnDeadline.start());
        assertThat(prepared.input().state().currentMicroGoalId()).isEqualTo(microGoal);
    }

    @Test
    void laterTurnCannotInventMissingCheckpointFocus() {
        var missing =
                new Scope(
                        scope.childId(),
                        scope.classId(),
                        scope.activityId(),
                        scope.goalId(),
                        scope.sessionId(),
                        scope.scenarioId(),
                        3,
                        7,
                        2,
                        null,
                        null,
                        true);
        assertThatThrownBy(
                        () ->
                                new RoleplayContextAssembler(
                                                key ->
                                                        Optional.of(
                                                                snapshot(
                                                                        key,
                                                                        state(microGoal, "S1", 3),
                                                                        List.of())))
                                        .prepareVoice(
                                                missing,
                                                request,
                                                turn,
                                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayContextAssembler.Unavailable.class);
    }

    @Test
    void closedScopeOrExpiredDeadlineDoesNotReadSource() {
        var calls = new AtomicInteger();
        var assembler =
                new RoleplayContextAssembler(
                        key -> {
                            calls.incrementAndGet();
                            return Optional.empty();
                        });
        var closed =
                new Scope(
                        scope.childId(),
                        scope.classId(),
                        scope.activityId(),
                        scope.goalId(),
                        scope.sessionId(),
                        scope.scenarioId(),
                        3,
                        7,
                        2,
                        microGoal,
                        "S1",
                        false);
        assertThatThrownBy(
                        () ->
                                assembler.prepareVoice(
                                        closed, request, turn, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayContextAssembler.Unavailable.class);
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        assertThatThrownBy(() -> assembler.prepareVoice(scope, request, turn, deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void sourceResultArrivingAfterCancellationCannotBeUsed() {
        var deadline = RoleplayTurnDeadline.start();
        var assembler =
                new RoleplayContextAssembler(
                        key -> {
                            deadline.cancel();
                            return Optional.of(snapshot(key, state(microGoal, "S1", 3), List.of()));
                        });
        assertThatThrownBy(() -> assembler.prepareVoice(scope, request, turn, deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
    }

    @Test
    void retainsOnlyLatestEightLinesAndDoesNotExposeContentInLogs() {
        var lines = new ArrayList<RoleplayTurnInput.DialogueLine>();
        for (int i = 0; i < 10; i++)
            lines.add(
                    new RoleplayTurnInput.DialogueLine(
                            RoleplayTurnInput.Speaker.AI, "synthetic secret line " + i));
        var snapshot = snapshot(key(), state(microGoal, "S1", 3), lines);
        lines.clear();
        var prepared =
                new RoleplayContextAssembler(key -> Optional.of(snapshot))
                        .prepareVoice(scope, request, turn, RoleplayTurnDeadline.start());
        assertThat(prepared.input().recentDialogue()).hasSize(8);
        assertThat(prepared.input().recentDialogue().getFirst().text())
                .isEqualTo("synthetic secret line 2");
        assertThat(prepared.input().recentDialogue().getLast().text())
                .isEqualTo("synthetic secret line 9");
        assertThat(snapshot.toString()).doesNotContain("synthetic secret", "synthetic goal");
        assertThat(prepared.toString()).doesNotContain("synthetic secret", "synthetic goal");
    }

    @Test
    void unapprovedContentCannotConstructSnapshot() {
        assertThatThrownBy(
                        () ->
                                new RoleplayContextSource.Snapshot(
                                        key(),
                                        "",
                                        scenario(),
                                        state(microGoal, "S1", 3),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RoleplayContextSource.Key key() {
        return new RoleplayContextSource.Key(
                scope.childId(),
                scope.activityId(),
                scope.goalId(),
                scope.sessionId(),
                scope.scenarioId(),
                3,
                7);
    }

    private RoleplayTurnInput.State state(UUID goal, String support, int turnCount) {
        return new RoleplayTurnInput.State(goal, support, 2, 1, false, turnCount, 77, 9);
    }

    private RoleplayTurnInput.Scenario scenario() {
        return new RoleplayTurnInput.Scenario(
                "synthetic goal",
                "L1",
                List.of("synthetic fact"),
                List.of(),
                List.of(
                        new RoleplayTurnInput.MicroGoal(
                                microGoal,
                                "synthetic micro goal",
                                List.of("synthetic evidence"),
                                "IN_PROGRESS")));
    }

    private RoleplayContextSource.Snapshot snapshot(
            RoleplayContextSource.Key key,
            RoleplayTurnInput.State state,
            List<RoleplayTurnInput.DialogueLine> lines) {
        return new RoleplayContextSource.Snapshot(
                key, "synthetic approval reference", scenario(), state, lines);
    }

    private void rejected(RoleplayContextSource.Snapshot snapshot) {
        assertThatThrownBy(
                        () ->
                                new RoleplayContextAssembler(key -> Optional.of(snapshot))
                                        .prepareVoice(
                                                scope, request, turn, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayContextAssembler.Unavailable.class);
    }
}
