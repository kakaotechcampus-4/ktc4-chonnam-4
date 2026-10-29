package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator.Decision;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Context;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.EvidenceReference;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Observation;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.ObservationStatus;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Proposal;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayGoalObservationPlanTest {
    private final UUID emotionGoal = UUID.randomUUID();
    private final UUID reasonGoal = UUID.randomUUID();
    private final Scope scope =
            new Scope(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    3,
                    "approval-3",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    7);
    private final EvidenceReference emotion =
            reference(scope.turnId(), emotionGoal, "emotion-evidence");
    private final EvidenceReference reason =
            reference(scope.turnId(), reasonGoal, "reason-evidence");

    @Test
    void observationAndNextQuestionCanReferToDifferentGoals() {
        var proposal = proposal(List.of(observation(emotion)), Decision.IN_PROGRESS, Set.of());
        var plan = RoleplayGoalObservationPlan.validateReferences(context(List.of()), proposal);

        assertThat(plan.proposal().observations())
                .extracting(o -> o.reference().microGoalId())
                .containsExactly(emotionGoal);
        assertThat(plan.proposal().nextFocusGoalId()).isEqualTo(reasonGoal);
        assertThat(plan.proposal().decision()).isEqualTo(Decision.IN_PROGRESS);
    }

    @Test
    void acceptsMultipleGoalsWithoutRequiringArrayOrderOrEveryGoal() {
        var proposal =
                proposal(
                        List.of(observation(reason), observation(emotion)),
                        Decision.ACHIEVED,
                        Set.of(emotion));
        var plan = RoleplayGoalObservationPlan.validateReferences(context(List.of()), proposal);

        assertThat(plan.proposal().observations()).hasSize(2);
        assertThat(plan.proposal().decisionBasis()).containsExactly(emotion);
    }

    @Test
    void completionCanUseConfirmedEvidenceFromAnEarlierTurn() {
        var prior = reference(UUID.randomUUID(), emotionGoal, "emotion-evidence");
        var proposal =
                proposal(List.of(observation(reason)), Decision.ACHIEVED, Set.of(prior, reason));

        var plan =
                RoleplayGoalObservationPlan.validateReferences(
                        context(List.of(observation(prior))), proposal);

        assertThat(plan.proposal().decisionBasis()).containsExactlyInAnyOrder(prior, reason);
    }

    @ParameterizedTest
    @EnumSource(ObservationStatus.class)
    void preservesObservationWithoutInventingAnActivityCompletionRule(ObservationStatus status) {
        var proposal =
                proposal(
                        List.of(new Observation(emotion, status)),
                        Decision.IN_PROGRESS,
                        Set.of(emotion));
        var plan = RoleplayGoalObservationPlan.validateReferences(context(List.of()), proposal);

        assertThat(plan.proposal().observations().getFirst().status()).isEqualTo(status);
        assertThat(plan.proposal().decision()).isEqualTo(Decision.IN_PROGRESS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "session",
                "scenario",
                "scenarioVersion",
                "approval",
                "turn",
                "candidate",
                "checkpoint"
            })
    void rejectsResultFromAnotherScope(String changed) {
        var wrong =
                new Scope(
                        changed.equals("session") ? UUID.randomUUID() : scope.sessionId(),
                        changed.equals("scenario") ? UUID.randomUUID() : scope.scenarioId(),
                        changed.equals("scenarioVersion") ? 4 : scope.scenarioVersion(),
                        changed.equals("approval") ? "approval-4" : scope.approvalReference(),
                        changed.equals("turn") ? UUID.randomUUID() : scope.turnId(),
                        changed.equals("candidate") ? UUID.randomUUID() : scope.candidateId(),
                        changed.equals("checkpoint") ? 8 : scope.checkpointVersion());
        var proposal =
                new Proposal(
                        wrong,
                        "analysis-test-v2",
                        "evaluation-test-v2",
                        List.of(observation(emotion)),
                        reasonGoal,
                        Decision.IN_PROGRESS,
                        Set.of());

        rejects(context(List.of()), proposal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysis", "evaluation"})
    void rejectsUnknownAnalysisOrEvaluationContract(String changed) {
        var proposal =
                new Proposal(
                        scope,
                        changed.equals("analysis") ? "other" : "analysis-test-v2",
                        changed.equals("evaluation") ? "other" : "evaluation-test-v2",
                        List.of(observation(emotion)),
                        reasonGoal,
                        Decision.IN_PROGRESS,
                        Set.of());

        rejects(context(List.of()), proposal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "criterion", "turn"})
    void rejectsUnapprovedOrForeignObservation(String changed) {
        var wrong =
                reference(
                        changed.equals("turn") ? UUID.randomUUID() : scope.turnId(),
                        changed.equals("goal") ? UUID.randomUUID() : emotionGoal,
                        changed.equals("criterion") ? "unknown-evidence" : "emotion-evidence");

        rejects(
                context(List.of()),
                proposal(List.of(observation(wrong)), Decision.IN_PROGRESS, Set.of()));
    }

    @Test
    void rejectsDuplicateObservationEvenWhenItsStatusDiffers() {
        rejects(
                context(List.of()),
                proposal(
                        List.of(
                                observation(emotion),
                                new Observation(emotion, ObservationStatus.CONTRADICTORY)),
                        Decision.IN_PROGRESS,
                        Set.of()));
    }

    @Test
    void rejectsEvidenceApprovedForAnotherGoal() {
        var wrong = reference(scope.turnId(), emotionGoal, "reason-evidence");
        rejects(
                context(List.of()),
                proposal(List.of(observation(wrong)), Decision.IN_PROGRESS, Set.of()));
    }

    @Test
    void rejectsUnknownNextQuestionGoal() {
        var proposal =
                new Proposal(
                        scope,
                        "analysis-test-v2",
                        "evaluation-test-v2",
                        List.of(observation(emotion)),
                        UUID.randomUUID(),
                        Decision.IN_PROGRESS,
                        Set.of());
        rejects(context(List.of()), proposal);
    }

    @Test
    void rejectsAchievementWithoutEvidence() {
        rejects(
                context(List.of()),
                proposal(List.of(observation(emotion)), Decision.ACHIEVED, Set.of()));
    }

    @Test
    void rejectsDecisionBasisThatWasNeverObserved() {
        rejects(
                context(List.of()),
                proposal(List.of(observation(emotion)), Decision.ACHIEVED, Set.of(reason)));
    }

    @Test
    void unavailableCannotBecomeAStorablePlan() {
        rejects(context(List.of()), proposal(List.of(), Decision.UNAVAILABLE, Set.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"currentTurn", "duplicate", "unapproved"})
    void rejectsInconsistentConfirmedHistory(String changed) {
        var prior =
                reference(
                        UUID.randomUUID(),
                        emotionGoal,
                        changed.equals("unapproved") ? "unknown" : "emotion-evidence");
        var history =
                switch (changed) {
                    case "currentTurn" -> List.of(observation(emotion));
                    case "duplicate" -> List.of(observation(prior), observation(prior));
                    default -> List.of(observation(prior));
                };
        rejects(
                context(history),
                proposal(List.of(observation(reason)), Decision.IN_PROGRESS, Set.of()));
    }

    @Test
    void validatedPlanCannotChangeThroughTheOriginalCollections() {
        var criteria = new HashSet<>(Set.of("emotion-evidence"));
        var goals = new HashMap<UUID, Set<String>>();
        goals.put(emotionGoal, criteria);
        goals.put(reasonGoal, Set.of("reason-evidence"));
        var history = new ArrayList<Observation>();
        var context = new Context(scope, goals, history, "analysis-test-v2", "evaluation-test-v2");
        var observations = new ArrayList<>(List.of(observation(emotion)));
        var basis = new HashSet<>(Set.of(emotion));
        var proposal =
                new Proposal(
                        scope,
                        "analysis-test-v2",
                        "evaluation-test-v2",
                        observations,
                        reasonGoal,
                        Decision.ACHIEVED,
                        basis);
        var plan = RoleplayGoalObservationPlan.validateReferences(context, proposal);

        criteria.clear();
        goals.clear();
        history.add(observation(reason));
        observations.clear();
        basis.clear();

        assertThat(context.approvedCriteria().get(emotionGoal)).containsExactly("emotion-evidence");
        assertThat(context.confirmedObservations()).isEmpty();
        assertThat(plan.proposal().observations()).containsExactly(observation(emotion));
        assertThat(plan.proposal().decisionBasis()).containsExactly(emotion);
        assertThatThrownBy(() -> plan.proposal().observations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> context.approvedCriteria().get(emotionGoal).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private Context context(List<Observation> history) {
        return new Context(
                scope,
                Map.of(
                        emotionGoal,
                        Set.of("emotion-evidence"),
                        reasonGoal,
                        Set.of("reason-evidence")),
                history,
                "analysis-test-v2",
                "evaluation-test-v2");
    }

    private Proposal proposal(
            List<Observation> observations, Decision decision, Set<EvidenceReference> basis) {
        return new Proposal(
                scope,
                "analysis-test-v2",
                "evaluation-test-v2",
                observations,
                reasonGoal,
                decision,
                basis);
    }

    private Observation observation(EvidenceReference reference) {
        return new Observation(reference, ObservationStatus.EXPLICIT);
    }

    private EvidenceReference reference(UUID turnId, UUID goalId, String criterion) {
        return new EvidenceReference(turnId, goalId, criterion);
    }

    private void rejects(Context context, Proposal proposal) {
        assertThatThrownBy(() -> RoleplayGoalObservationPlan.validateReferences(context, proposal))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
