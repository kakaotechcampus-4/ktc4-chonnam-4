package com.neuringo.neuringobe.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.report.domain.EvidenceSufficiency;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.RecommendationCandidate;
import com.neuringo.neuringobe.report.domain.RecommendationCandidate.Reason;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationDraftsTest {
    private final UUID goalId = UUID.randomUUID();
    private final RecommendationDrafts.CurrentGoal current =
            new RecommendationDrafts.CurrentGoal(goalId, "넘어진 친구의 속상한 마음 알아보기", "EMPATHY");

    @Test
    void keepsAtMostThreeInPolicyOrderAndCarriesTheParentGoal() {
        var drafts =
                RecommendationDrafts.from(
                        List.of(
                                candidate(RecommendationType.REINFORCE, Reason.GOAL_NOT_REACHED),
                                candidate(
                                        RecommendationType.REPEAT,
                                        Reason.REACHED_WITH_EXTRA_SUPPORT),
                                candidate(
                                        RecommendationType.APPLY,
                                        Reason.REACHED_WITHOUT_EXTRA_SUPPORT),
                                candidate(
                                        RecommendationType.EXTEND,
                                        Reason.ALL_GOALS_WITHOUT_EXTRA_SUPPORT)),
                        current,
                        ScenarioLevel.L2,
                        true);

        assertThat(drafts)
                .extracting(RecommendationDrafts.Draft::type)
                .containsExactly(
                        RecommendationType.REINFORCE,
                        RecommendationType.REPEAT,
                        RecommendationType.APPLY);
        assertThat(drafts)
                .extracting(RecommendationDrafts.Draft::displayOrder)
                .containsExactly(1, 2, 3);
        assertThat(drafts.get(1).reason()).contains("'위로하는 말 하기' 목표는 두 선택지를 받고 도달했어요");
        assertThat(drafts.getFirst().suggestedGoal())
                .isEqualTo(
                        new RecommendationDrafts.SuggestedGoal(current.title(), "EMPATHY", goalId));
    }

    @Test
    void extendLeavesTheTitleForTheInstructorAndCanBeTurnedOffAtL1() {
        var extend =
                List.of(
                        candidate(
                                RecommendationType.EXTEND, Reason.ALL_GOALS_WITHOUT_EXTRA_SUPPORT));

        var onL2 = RecommendationDrafts.from(extend, current, ScenarioLevel.L2, false);
        var onL1 = RecommendationDrafts.from(extend, current, ScenarioLevel.L1, false);
        var onL1Default = RecommendationDrafts.from(extend, current, ScenarioLevel.L1, true);

        assertThat(onL2.getFirst().suggestedGoal().title()).isNull();
        assertThat(onL2.getFirst().suggestedGoal().parentGoalId()).isEqualTo(goalId);
        assertThat(onL1).isEmpty();
        assertThat(onL1Default).hasSize(1);
    }

    private RecommendationCandidate candidate(RecommendationType type, Reason reason) {
        var outcome =
                new GoalOutcome(
                        UUID.randomUUID(),
                        SupportLevel.S1,
                        SupportLevel.S2,
                        GoalOutcome.Result.REACHED);
        return new RecommendationCandidate(
                type,
                reason,
                new GoalEvidence(outcome, "위로하는 말 하기", List.of(), List.of()),
                List.of("두 선택지를 들은 뒤 목표에 맞게 답함"),
                EvidenceSufficiency.SUFFICIENT);
    }
}
