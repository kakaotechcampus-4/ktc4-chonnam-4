package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.structured.output.*;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayInputGoalAssessmentTest {
    private final UUID goal = UUID.randomUUID(),
            nextGoal = UUID.randomUUID(),
            turn = UUID.randomUUID();
    private final List<Observation> positive =
            List.of(
                    observation(goal, "a", Status.EXPLICIT),
                    observation(goal, "b", Status.IMPLICIT));

    @ParameterizedTest
    @CsvSource({"S0,ACHIEVED_WITHOUT_HINT", "S1,ACHIEVED_WITH_SUPPORT", "S2,ACHIEVED_WITH_SUPPORT"})
    void reviewedAchievementRetainsEveryCurrentGoalsCriterionAndActualSupport(
            String support, String outcome) {
        var assessment = assess(support, false, "GOAL_ACHIEVED", positive, null, nextGoal, "S0");
        assertThat(assessment.outcome().name()).isEqualTo(outcome);
        assertThat(assessment.supportDecision())
                .isEqualTo(RoleplayInputGoalAssessment.SupportDecision.NEXT_GOAL_START);
        assertThat(assessment.basis())
                .hasSize(2)
                .allSatisfy(
                        ref -> {
                            assertThat(ref.microGoalId()).isEqualTo(goal);
                            assertThat(ref.turnId()).isEqualTo(turn);
                        });
    }

    @Test
    void previouslyProvidedCluesRemainAssistedEvenWhenCurrentSupportIsS0() {
        assertThat(assess("S0", false, "GOAL_ACHIEVED", positive, "S2", nextGoal, "S0").outcome())
                .isEqualTo(RoleplayInputGoalAssessment.Outcome.ACHIEVED_WITH_SUPPORT);
    }

    @Test
    void answerRevealCannotBecomeAchievementEvenWhenTheModelSaysAchieved() {
        var assessment = assess("S3", true, "GOAL_ACHIEVED", positive, "S3", nextGoal, "S1");
        assertThat(assessment.outcome())
                .isEqualTo(RoleplayInputGoalAssessment.Outcome.SUPPORTED_NOT_ACHIEVED);
        assertThat(assessment.basis()).isEmpty();
    }

    @Test
    void positiveObservationsDoNotOverrideTheReviewedInProgressVerdict() {
        var assessment = assess("S0", false, "PARTIAL_UNDERSTANDING", positive, null, goal, "S0");
        assertThat(assessment.outcome()).isEqualTo(RoleplayInputGoalAssessment.Outcome.IN_PROGRESS);
        assertThat(assessment.supportDecision())
                .isEqualTo(RoleplayInputGoalAssessment.SupportDecision.MAINTAINED);
        assertThat(assessment.basis()).isEmpty();
    }

    @Test
    void helpRequestRetainsTheReasonForIncreasingCurrentGoalsSupport() {
        var assessment = assess("S1", false, "HELP_NEEDED", positive, null, goal, "S2");
        assertThat(assessment.outcome()).isEqualTo(RoleplayInputGoalAssessment.Outcome.IN_PROGRESS);
        assertThat(assessment.supportDecision())
                .isEqualTo(RoleplayInputGoalAssessment.SupportDecision.INCREASED);
    }

    @Test
    void notAssessableRemainsExcludedEvenWhenPositiveObservationsExist() {
        var assessment = assess("S1", false, "NOT_ASSESSABLE", positive, null, goal, "S1");
        assertThat(assessment.outcome())
                .isEqualTo(RoleplayInputGoalAssessment.Outcome.NOT_ASSESSABLE);
        assertThat(assessment.basis()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "support"})
    void notAssessableCannotChangeQuestionFocusOrSupport(String change) {
        assertThatThrownBy(
                        () ->
                                assess(
                                        "S1",
                                        false,
                                        "NOT_ASSESSABLE",
                                        positive,
                                        null,
                                        change.equals("goal") ? nextGoal : goal,
                                        change.equals("support") ? "S2" : "S1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "negative", "otherGoal", "pastTurn", "duplicate"})
    void achievementRequiresAllCurrentGoalsPositiveCriteriaFromThisInput(String change) {
        var bad =
                switch (change) {
                    case "missing" -> List.of(positive.getFirst());
                    case "negative" ->
                            List.of(
                                    positive.getFirst(),
                                    observation(goal, "b", Status.CONTRADICTORY));
                    case "otherGoal" -> List.of(observation(nextGoal, "c", Status.EXPLICIT));
                    case "pastTurn" ->
                            List.of(
                                    new Observation(
                                            new Reference(UUID.randomUUID(), goal, "a"),
                                            Status.EXPLICIT),
                                    positive.getLast());
                    default ->
                            List.of(positive.getFirst(), positive.getFirst(), positive.getLast());
                };
        assertThatThrownBy(() -> assess("S0", false, "GOAL_ACHIEVED", bad, null, nextGoal, "S0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Observation observation(UUID observedGoal, String criterion, Status status) {
        return new Observation(new Reference(turn, observedGoal, criterion), status);
    }

    private RoleplayInputGoalAssessment assess(
            String support,
            boolean revealed,
            String learning,
            List<Observation> observations,
            String priorSupport,
            UUID target,
            String nextSupport) {
        var prior =
                priorSupport == null
                        ? null
                        : new GoalObservationInput.PriorResponse(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                goal,
                                1,
                                1,
                                priorSupport,
                                revealed ? "ANSWER_REVEAL" : "GUIDING_QUESTION");
        var input =
                new GoalObservationInput(
                        List.of(
                                new GoalObservationInput.Criterion(goal, "a", "감정 설명"),
                                new GoalObservationInput.Criterion(goal, "b", "이유 설명"),
                                new GoalObservationInput.Criterion(nextGoal, "c", "다음 목표 증거")),
                        List.of(),
                        List.of(),
                        List.of(
                                new GoalObservationInput.CurrentGoalExposure(
                                        goal,
                                        new GoalObservationInput.GoalExposure(
                                                prior == null || revealed ? 0 : 1,
                                                revealed,
                                                prior,
                                                priorSupport)),
                                new GoalObservationInput.CurrentGoalExposure(
                                        nextGoal,
                                        new GoalObservationInput.GoalExposure(
                                                0, false, null, null))));
        UUID analysisId = UUID.randomUUID(), candidateId = UUID.randomUUID();
        var analysis =
                new AnalysisResult(
                        turn,
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        learning,
                        new AnalysisResult.PrimaryGap("NONE", null),
                        new AnalysisResult.NextStrategy("ADVANCE_GOAL", target, null, nextSupport),
                        0.9,
                        new Analysis(
                                GoalObservationOutput.ANALYSIS_VERSION,
                                analysisId,
                                observations,
                                new Assessment(ActivityDecision.IN_PROGRESS, List.of())));
        var candidate =
                new CandidateResponse(
                        candidateId,
                        turn,
                        "합성 질문?",
                        "GUIDING_QUESTION",
                        "ADVANCE_GOAL",
                        target,
                        nextSupport,
                        List.of());
        var evaluation =
                new EvaluationResult(
                        candidateId,
                        true,
                        EvaluationDecision.PASS,
                        null,
                        0,
                        List.of(),
                        null,
                        new Review(
                                GoalObservationOutput.EVALUATION_VERSION, analysisId, true, true));
        return RoleplayInputGoalAssessment.assess(
                new RoleplayInputContext(goal, support, 1, 0, revealed),
                input,
                new RoleplayTurnResult.Ready(analysis, candidate, evaluation));
    }
}
