package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayGoalObservationHistoryTest {
    private final UUID event = UUID.randomUUID();
    private final UUID emotion = UUID.randomUUID();

    @Test
    void oneInputCanObserveTwoGoalsWithoutCopyingItsSupportToTheOtherGoal() {
        var first = response(1, emotion, "S2", "GUIDING_QUESTION", input(event, "S1", 1, 0, false));
        var observations = List.of(observation(first, event), observation(first, emotion));
        var linked = RoleplayGoalObservationHistory.link(observations, history(first));
        assertThat(linked)
                .hasSize(2)
                .allSatisfy(
                        context -> {
                            assertThat(context.inputContext()).isEqualTo(first.inputContext());
                            assertThat(context.candidateId()).isEqualTo(first.candidateId());
                            assertThat(context.goalExposureBeforeInput())
                                    .isEqualTo(
                                            new GoalObservationInput.GoalExposure(0, false, null));
                        });
        assertThat(linked)
                .extracting(c -> c.reference().microGoalId())
                .containsExactly(event, emotion);
        assertThat(history(first).inputHistory().exactAttemptCount(emotion)).hasValue(0);
        assertThat(
                        new GoalObservationInput(
                                        criteria(),
                                        observations,
                                        linked,
                                        RoleplayGoalObservationHistory.beforeNextInput(
                                                history(first)))
                                .confirmedObservationContexts())
                .isEqualTo(linked);
    }

    @Test
    void observedGoalKeepsItsEarlierAnswerRevealEvenWhenInputFocusHasChanged() {
        var first = response(1, emotion, "S3", "ANSWER_REVEAL", input(event, "S1", 1, 0, false));
        var second = response(2, event, "S1", "GUIDING_QUESTION", input(emotion, "S3", 1, 0, true));
        var third = response(3, event, "S2", "GUIDING_QUESTION", input(event, "S1", 2, 1, false));
        var context =
                RoleplayGoalObservationHistory.link(
                                List.of(observation(third, emotion)), history(first, second, third))
                        .getFirst();
        assertThat(context.inputContext().microGoalId()).isEqualTo(event);
        assertThat(context.inputContext().supportLevel()).isEqualTo("S1");
        assertThat(context.inputContext().answerRevealed()).isFalse();
        var exposure = context.goalExposureBeforeInput();
        assertThat(exposure.answerRevealed()).isTrue();
        assertThat(exposure.questionCount()).isZero();
        assertThat(exposure.lastResponse().turnId()).isEqualTo(first.turnId());
        assertThat(exposure.lastResponse().candidateId()).isEqualTo(first.candidateId());
        assertThat(exposure.lastResponse().microGoalId()).isEqualTo(emotion);
        assertThat(exposure.lastResponse().supportLevel()).isEqualTo("S3");
    }

    @Test
    void currentAndFutureResponsesCannotBecomeSupportForAnEarlierObservation() {
        var first = response(1, event, "S1", "GUIDING_QUESTION", input(event, "S1", 1, 0, false));
        var second = response(2, event, "S3", "ANSWER_REVEAL", input(event, "S1", 2, 1, false));
        var third = response(3, event, "S1", "GUIDING_QUESTION", input(event, "S3", 3, 1, true));
        var history = history(first, second, third);
        var linked =
                RoleplayGoalObservationHistory.link(
                        List.of(observation(first, event), observation(second, event)), history);
        assertThat(history.byGoal().get(event).answerRevealed()).isTrue();
        assertThat(linked.getFirst().goalExposureBeforeInput())
                .isEqualTo(new GoalObservationInput.GoalExposure(0, false, null));
        var beforeSecond = linked.get(1).goalExposureBeforeInput();
        assertThat(beforeSecond.questionCount()).isEqualTo(1);
        assertThat(beforeSecond.answerRevealed()).isFalse();
        assertThat(beforeSecond.lastResponse().turnId()).isEqualTo(first.turnId());
        assertThat(
                        RoleplayGoalObservationHistory.link(
                                        List.of(observation(second, event)), history(first, second))
                                .getFirst())
                .isEqualTo(linked.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreignTurn", "unknownInput", "foreignGoal", "duplicate"})
    void untraceableOrDuplicatedObservationsAreRejected(String changed) {
        var first =
                response(
                        1,
                        event,
                        "S1",
                        "GUIDING_QUESTION",
                        changed.equals("unknownInput") ? null : input(event, "S1", 1, 0, false));
        var observation =
                new Observation(
                        new Reference(
                                changed.equals("foreignTurn") ? UUID.randomUUID() : first.turnId(),
                                changed.equals("foreignGoal") ? UUID.randomUUID() : event,
                                "event-proof"),
                        Status.EXPLICIT);
        var observations =
                changed.equals("duplicate")
                        ? List.of(observation, observation)
                        : List.of(observation);
        assertThatThrownBy(() -> RoleplayGoalObservationHistory.link(observations, history(first)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "duplicate",
                "foreignReference",
                "foreignInputGoal",
                "sameTurnPrior",
                "futurePrior",
                "otherGoalPrior",
                "conflictingSameTurn"
            })
    void promptInputRejectsMissingOrInconsistentObservationProvenance(String changed) {
        var turn = UUID.randomUUID();
        var observation =
                new Observation(new Reference(turn, event, "event-proof"), Status.EXPLICIT);
        var second =
                new Observation(new Reference(turn, emotion, "emotion-proof"), Status.EXPLICIT);
        var reference =
                changed.equals("foreignReference")
                        ? new Reference(UUID.randomUUID(), event, "event-proof")
                        : observation.reference();
        var input =
                input(
                        changed.equals("foreignInputGoal") ? UUID.randomUUID() : event,
                        "S1",
                        1,
                        0,
                        false);
        GoalObservationInput.PriorResponse prior = null;
        if (Set.of("sameTurnPrior", "futurePrior", "otherGoalPrior").contains(changed)) {
            prior =
                    new GoalObservationInput.PriorResponse(
                            changed.equals("sameTurnPrior") ? turn : UUID.randomUUID(),
                            UUID.randomUUID(),
                            changed.equals("otherGoalPrior") ? emotion : event,
                            changed.equals("futurePrior") ? 3 : 1,
                            1,
                            "S1",
                            "GUIDING_QUESTION");
        }
        var context =
                new GoalObservationInput.ObservationContext(
                        reference,
                        UUID.randomUUID(),
                        2,
                        2,
                        input,
                        new GoalObservationInput.GoalExposure(prior == null ? 0 : 1, false, prior));
        var contexts =
                changed.equals("missing")
                        ? List.<GoalObservationInput.ObservationContext>of()
                        : changed.equals("duplicate")
                                ? List.of(context, context)
                                : changed.equals("conflictingSameTurn")
                                        ? List.of(
                                                context,
                                                new GoalObservationInput.ObservationContext(
                                                        second.reference(),
                                                        UUID.randomUUID(),
                                                        2,
                                                        2,
                                                        input,
                                                        new GoalObservationInput.GoalExposure(
                                                                0, false, null)))
                                        : List.of(context);
        var observations =
                changed.equals("conflictingSameTurn")
                        ? List.of(observation, second)
                        : List.of(observation);
        assertThatThrownBy(
                        () ->
                                new GoalObservationInput(
                                        criteria(),
                                        observations,
                                        contexts,
                                        RoleplayObservationTestPlans.withoutPriorSupport(
                                                criteria())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentExposureIncludesEveryGoalAndPreservesDifferentSupportHistories() {
        var first = response(1, emotion, "S3", "ANSWER_REVEAL", input(event, "S1", 1, 0, false));
        var second = response(2, event, "S1", "GUIDING_QUESTION", input(emotion, "S3", 1, 0, true));
        var exposures = RoleplayGoalObservationHistory.beforeNextInput(history(first, second));
        assertThat(exposures)
                .extracting(GoalObservationInput.CurrentGoalExposure::microGoalId)
                .containsExactlyInAnyOrder(event, emotion);
        var emotionExposure =
                exposures.stream()
                        .filter(e -> e.microGoalId().equals(emotion))
                        .findFirst()
                        .orElseThrow()
                        .exposureBeforeInput();
        assertThat(emotionExposure.answerRevealed()).isTrue();
        assertThat(emotionExposure.questionCount()).isZero();
        assertThat(emotionExposure.lastResponse().turnId()).isEqualTo(first.turnId());
        assertThat(emotionExposure.lastResponse().supportLevel()).isEqualTo("S3");
        var eventExposure =
                exposures.stream()
                        .filter(e -> e.microGoalId().equals(event))
                        .findFirst()
                        .orElseThrow()
                        .exposureBeforeInput();
        assertThat(eventExposure.answerRevealed()).isFalse();
        assertThat(eventExposure.questionCount()).isEqualTo(1);
        assertThat(eventExposure.lastResponse().turnId()).isEqualTo(second.turnId());
        assertThat(eventExposure.lastResponse().supportLevel()).isEqualTo("S1");
        var snapshot = new GoalObservationInput(criteria(), List.of(), List.of(), exposures);
        assertThat(snapshot.currentGoalExposures()).isEqualTo(exposures);
    }

    @Test
    void anUnvisitedApprovedGoalHasNoRecordedResponseWithoutInventingS0() {
        var first = response(1, event, "S2", "GUIDING_QUESTION", input(event, "S1", 1, 0, false));
        var exposures = RoleplayGoalObservationHistory.beforeNextInput(history(first));
        assertThat(
                        exposures.stream()
                                .filter(e -> e.microGoalId().equals(emotion))
                                .findFirst()
                                .orElseThrow()
                                .exposureBeforeInput())
                .isEqualTo(new GoalObservationInput.GoalExposure(0, false, null));
        var empty =
                RoleplayGoalResponseHistory.restore(
                        Set.of(event, emotion),
                        new RoleplayGoalResponseHistory.Checkpoint(0, 0, null, event, "S1"),
                        List.of());
        assertThat(RoleplayGoalObservationHistory.beforeNextInput(empty))
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.exposureBeforeInput().lastResponse()).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "foreignGoal", "otherGoalPrior"})
    void currentExposureMustCoverExactlyTheApprovedGoals(String changed) {
        var valid = RoleplayObservationTestPlans.withoutPriorSupport(criteria());
        var invalidGoal = changed.equals("foreignGoal") ? UUID.randomUUID() : event;
        var prior =
                changed.equals("otherGoalPrior")
                        ? new GoalObservationInput.PriorResponse(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                emotion,
                                1,
                                1,
                                "S3",
                                "ANSWER_REVEAL")
                        : null;
        var changedExposure =
                new GoalObservationInput.CurrentGoalExposure(
                        invalidGoal,
                        new GoalObservationInput.GoalExposure(0, prior != null, prior));
        var exposures =
                changed.equals("missing")
                        ? List.<GoalObservationInput.CurrentGoalExposure>of()
                        : changed.equals("duplicate")
                                ? List.of(valid.getFirst(), valid.getFirst())
                                : List.of(
                                        changedExposure,
                                        valid.stream()
                                                .filter(e -> e.microGoalId().equals(emotion))
                                                .findFirst()
                                                .orElseThrow());
        assertThatThrownBy(
                        () -> new GoalObservationInput(criteria(), List.of(), List.of(), exposures))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<GoalObservationInput.Criterion> criteria() {
        return List.of(
                new GoalObservationInput.Criterion(event, "event-proof", "사건 설명"),
                new GoalObservationInput.Criterion(emotion, "emotion-proof", "감정 설명"));
    }

    private Observation observation(RoleplayGoalResponseHistory.Response response, UUID goal) {
        return new Observation(
                new Reference(
                        response.turnId(),
                        goal,
                        goal.equals(event) ? "event-proof" : "emotion-proof"),
                Status.EXPLICIT);
    }

    private RoleplayInputContext input(
            UUID goal, String support, int attempt, int questions, boolean revealed) {
        return new RoleplayInputContext(goal, support, attempt, questions, revealed);
    }

    private RoleplayGoalResponseHistory.Response response(
            int number,
            UUID nextGoal,
            String nextSupport,
            String type,
            RoleplayInputContext input) {
        return new RoleplayGoalResponseHistory.Response(
                UUID.randomUUID(),
                UUID.randomUUID(),
                number,
                number,
                nextGoal,
                nextSupport,
                type,
                "PROBE_EVENT",
                input);
    }

    private RoleplayGoalResponseHistory history(RoleplayGoalResponseHistory.Response... responses) {
        var last = responses[responses.length - 1];
        return RoleplayGoalResponseHistory.restore(
                Set.of(event, emotion),
                new RoleplayGoalResponseHistory.Checkpoint(
                        responses.length,
                        last.checkpointVersion(),
                        last.turnId(),
                        last.microGoalId(),
                        last.supportLevel()),
                List.of(responses));
    }
}
