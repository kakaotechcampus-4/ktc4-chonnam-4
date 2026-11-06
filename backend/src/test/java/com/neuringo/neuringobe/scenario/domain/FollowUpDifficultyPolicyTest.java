package com.neuringo.neuringobe.scenario.domain;

import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.EXCLUDED_INTERRUPTED;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.EXCLUDED_REFUSED;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.EXCLUDED_TECHNICAL_ERROR;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.NOT_REACHED;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.REACHED;
import static com.neuringo.neuringobe.scenario.domain.ScenarioLevel.L1;
import static com.neuringo.neuringobe.scenario.domain.ScenarioLevel.L2;
import static com.neuringo.neuringobe.scenario.domain.ScenarioLevel.L3;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S0;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S1;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S2;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Direction;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Reason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 03 VS-016 인수 조건과 04 "통합: 경계값·L1/L3·유효 목표 부족 테스트"를 정책 단위로 확인한다. */
class FollowUpDifficultyPolicyTest {

    private final FollowUpDifficultyPolicy policy =
            new FollowUpDifficultyPolicy(FollowUpDifficultyRules.V1);

    @Test
    void scoresSupportLevelsFromS0StartAsThreeTwoOneZero() {
        assertThat(rate(goal(S0, S0, REACHED)).ratio()).isEqualTo(1.0);
        assertThat(rate(goal(S0, S1, REACHED)).ratio()).isEqualTo(4 / 6.0);
        assertThat(rate(goal(S0, S2, REACHED)).ratio()).isEqualTo(2 / 6.0);
        assertThat(rate(goal(S0, S3, REACHED)).ratio()).isZero();
        assertThat(rate(goal(S0, S2, NOT_REACHED)).ratio()).isZero();
    }

    @Test
    void normalizesS1AndS2StartsByRemainingSupportSteps() {
        assertThat(rate(goal(S1, S1, REACHED)).ratio()).isEqualTo(1.0);
        assertThat(rate(goal(S1, S2, REACHED)).ratio()).isEqualTo(0.5);
        assertThat(rate(goal(S2, S2, REACHED)).ratio()).isEqualTo(1.0);
        assertThat(rate(goal(S2, S3, REACHED)).ratio()).isZero();
    }

    @Test
    void doesNotExceedFullScoreWhenSupportWasLoweredBelowStart() {
        assertThat(rate(goal(S1, S0, REACHED)).ratio()).isEqualTo(1.0);
    }

    @Test
    void lowersBelowFiftyPercent() {
        FollowUpDifficultyDecision decision =
                policy.decide(
                        L2,
                        goals(goal(S0, S2, REACHED), goal(S0, S2, REACHED), goal(S0, S1, REACHED)),
                        0);

        // S3·미도달은 없고 (2 + 2 + 4) / 18 = 44%
        assertThat(decision.direction()).isEqualTo(Direction.LOWER);
        assertThat(decision.reason()).isEqualTo(Reason.SCORE_BELOW_THRESHOLD);
        assertThat(decision.nextLevel()).isEqualTo(L1);
    }

    @Test
    void keepsAtExactlyFiftyPercent() {
        // S1 시작 S2 도달 = 0.5, 두 목표 평균 50%
        FollowUpDifficultyDecision decision =
                policy.decide(L2, goals(goal(S1, S2, REACHED), goal(S1, S2, REACHED)), 0);

        assertThat(decision.scoreRate().ratio()).isEqualTo(0.5);
        assertThat(decision.direction()).isEqualTo(Direction.KEEP);
        assertThat(decision.reason()).isEqualTo(Reason.SCORE_IN_KEEP_RANGE);
    }

    @Test
    void lowersWhenHalfOfValidGoalsNeededAnswerOrWereMissedEvenAtFiftyPercent() {
        FollowUpDifficultyDecision decision =
                policy.decide(L2, goals(goal(S0, S0, REACHED), goal(S0, S3, REACHED)), 0);

        assertThat(decision.scoreRate().ratio()).isEqualTo(0.5);
        assertThat(decision.direction()).isEqualTo(Direction.LOWER);
        assertThat(decision.reason()).isEqualTo(Reason.ANSWER_OR_MISSED_MAJORITY);
    }

    @Test
    void keepsJustBelowEightyPercent() {
        // 6 + 6 + 6 + 2 + 2 = 22 / 30 = 73%
        FollowUpDifficultyDecision decision =
                policy.decide(
                        L2,
                        goals(
                                goal(S0, S0, REACHED),
                                goal(S0, S0, REACHED),
                                goal(S0, S0, REACHED),
                                goal(S0, S2, REACHED),
                                goal(S0, S2, REACHED)),
                        1);

        assertThat(decision.direction()).isEqualTo(Direction.KEEP);
        assertThat(decision.reason()).isEqualTo(Reason.SCORE_IN_KEEP_RANGE);
        assertThat(decision.consecutiveUpgradeCandidates()).isZero();
    }

    @Test
    void becomesUpgradeCandidateAtExactlyEightyPercentAndWaitsForSecond() {
        // 6 + 6 + 6 + 4 + 2 = 24 / 30 = 80%
        List<GoalOutcome> eightyPercent =
                goals(
                        goal(S0, S0, REACHED),
                        goal(S0, S0, REACHED),
                        goal(S0, S0, REACHED),
                        goal(S0, S1, REACHED),
                        goal(S0, S2, REACHED));

        FollowUpDifficultyDecision first = policy.decide(L2, eightyPercent, 0);
        assertThat(first.direction()).isEqualTo(Direction.KEEP);
        assertThat(first.reason()).isEqualTo(Reason.UPGRADE_CANDIDATE_PENDING);
        assertThat(first.consecutiveUpgradeCandidates()).isEqualTo(1);

        FollowUpDifficultyDecision second =
                policy.decide(L2, eightyPercent, first.consecutiveUpgradeCandidates());
        assertThat(second.direction()).isEqualTo(Direction.RAISE);
        assertThat(second.reason()).isEqualTo(Reason.UPGRADE_CANDIDATE_CONFIRMED);
        assertThat(second.nextLevel()).isEqualTo(L3);
        assertThat(second.consecutiveUpgradeCandidates()).isZero();
    }

    @Test
    void highScoreWithAnAnsweredGoalIsNotAnUpgradeCandidate() {
        List<GoalOutcome> goals = new ArrayList<>();
        for (int i = 0; i < 4; i++) goals.add(goal(S0, S0, REACHED));
        goals.add(goal(S0, S3, REACHED)); // 24 / 30 = 80% 이지만 S3 가 있다

        FollowUpDifficultyDecision decision = policy.decide(L2, goals, 1);

        assertThat(decision.direction()).isEqualTo(Direction.KEEP);
        assertThat(decision.reason()).isEqualTo(Reason.HIGH_SCORE_WITH_ANSWER_OR_MISSED);
        assertThat(decision.consecutiveUpgradeCandidates()).isZero();
    }

    @Test
    void keepsWhenFewerThanTwoValidGoalsAfterExclusions() {
        FollowUpDifficultyDecision decision =
                policy.decide(
                        L2,
                        goals(
                                goal(S0, S3, NOT_REACHED),
                                goal(S0, S0, EXCLUDED_TECHNICAL_ERROR),
                                goal(S0, S0, EXCLUDED_INTERRUPTED),
                                goal(S0, S0, EXCLUDED_REFUSED)),
                        1);

        assertThat(decision.direction()).isEqualTo(Direction.KEEP);
        assertThat(decision.reason()).isEqualTo(Reason.INSUFFICIENT_VALID_GOALS);
        assertThat(decision.consecutiveUpgradeCandidates()).isZero();
    }

    @Test
    void technicalErrorsDoNotLowerDifficulty() {
        FollowUpDifficultyDecision decision =
                policy.decide(
                        L2,
                        goals(
                                goal(S0, S0, REACHED),
                                goal(S0, S1, REACHED),
                                goal(S0, S3, EXCLUDED_TECHNICAL_ERROR),
                                goal(S0, S3, EXCLUDED_TECHNICAL_ERROR)),
                        0);

        // 제외 두 개를 빼면 (6 + 4) / 12 = 83%
        assertThat(decision.scoreRate().goalCount()).isEqualTo(2);
        assertThat(decision.direction()).isNotEqualTo(Direction.LOWER);
    }

    @Test
    void staysAtL1WhenLoweringFromLowest() {
        FollowUpDifficultyDecision decision =
                policy.decide(L1, goals(goal(S0, S3, NOT_REACHED), goal(S0, S3, NOT_REACHED)), 0);

        assertThat(decision.nextLevel()).isEqualTo(L1);
        assertThat(decision.direction()).isEqualTo(Direction.KEEP);
        assertThat(decision.reason()).isEqualTo(Reason.ALREADY_LOWEST);
    }

    @Test
    void staysAtL3WhenRaisingFromHighest() {
        FollowUpDifficultyDecision decision =
                policy.decide(L3, goals(goal(S0, S0, REACHED), goal(S0, S0, REACHED)), 1);

        assertThat(decision.nextLevel()).isEqualTo(L3);
        assertThat(decision.direction()).isEqualTo(Direction.KEEP);
        assertThat(decision.reason()).isEqualTo(Reason.ALREADY_HIGHEST);
    }

    @Test
    void keepsCurrentLevelWhenInputCannotBeCalculated() {
        GoalOutcome duplicated = goal(S0, S0, REACHED);

        assertThat(policy.decide(L2, null, 0).reason()).isEqualTo(Reason.CALCULATION_FAILED);
        assertThat(policy.decide(L2, Arrays.asList(duplicated, null), 0).reason())
                .isEqualTo(Reason.CALCULATION_FAILED);
        FollowUpDifficultyDecision decision = policy.decide(L2, goals(duplicated, duplicated), 0);
        assertThat(decision.reason()).isEqualTo(Reason.CALCULATION_FAILED);
        assertThat(decision.nextLevel()).isEqualTo(L2);
        assertThat(policy.decide(L2, goals(goal(S0, S0, REACHED)), -1).reason())
                .isEqualTo(Reason.CALCULATION_FAILED);
    }

    @Test
    void fastUpgradeIsOffByDefaultAndRaisesOnFirstCandidateWhenEnabled() {
        List<GoalOutcome> perfect = goals(goal(S0, S0, REACHED), goal(S0, S0, REACHED));
        assertThat(policy.decide(L1, perfect, 0).reason())
                .isEqualTo(Reason.UPGRADE_CANDIDATE_PENDING);

        FollowUpDifficultyPolicy withFastUpgrade =
                new FollowUpDifficultyPolicy(
                        new FollowUpDifficultyRules("FOLLOW_UP_ROLEPLAY_FAST", 50, 80, 2, 2, 90));
        FollowUpDifficultyDecision decision = withFastUpgrade.decide(L1, perfect, 0);
        assertThat(decision.reason()).isEqualTo(Reason.FAST_UPGRADE);
        assertThat(decision.nextLevel()).isEqualTo(L2);
        assertThat(decision.policyVersion()).isEqualTo("FOLLOW_UP_ROLEPLAY_FAST");
    }

    @Test
    void recordsPolicyVersionAndIsDeterministic() {
        List<GoalOutcome> goals = goals(goal(S0, S1, REACHED), goal(S1, S1, REACHED));

        FollowUpDifficultyDecision first = policy.decide(L2, goals, 0);
        FollowUpDifficultyDecision second = policy.decide(L2, goals, 0);

        assertThat(first).isEqualTo(second);
        assertThat(first.policyVersion()).isEqualTo("FOLLOW_UP_ROLEPLAY_V1");
    }

    @Test
    void decisionRejectsMovingMoreThanOneLevel() {
        assertThatThrownBy(
                        () ->
                                new FollowUpDifficultyDecision(
                                        L1,
                                        L3,
                                        Direction.RAISE,
                                        Reason.UPGRADE_CANDIDATE_CONFIRMED,
                                        new GoalScoreRate(0, 0),
                                        0,
                                        "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GoalScoreRate rate(GoalOutcome... goals) {
        return GoalScoreRate.of(List.of(goals));
    }

    private static List<GoalOutcome> goals(GoalOutcome... goals) {
        return List.of(goals);
    }

    private static GoalOutcome goal(
            SupportLevel start, SupportLevel finalSupport, GoalOutcome.Result result) {
        return new GoalOutcome(UUID.randomUUID(), start, finalSupport, result);
    }
}
