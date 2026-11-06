package com.neuringo.neuringobe.scenario.domain;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Direction;
import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyDecision.Reason;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 직전 완료 역할극 한 건의 목표별 결과로 다음 시나리오 난이도를 정한다(03 VS-016, 06 확정 정책표).
 *
 * <ul>
 *   <li>하향: 점수율이 하향 기준 미만이거나, S3·미도달 목표가 유효 목표의 절반 이상
 *   <li>유지: 점수율이 하향 기준 이상 상향 기준 미만, 또는 근거 부족·계산 실패
 *   <li>상향 후보: 점수율이 상향 기준 이상이고 모든 유효 목표가 S0~S2 에서 도달. 연속 후보 수가 기준에 닿으면 상향한다
 * </ul>
 *
 * <p>한 번에 한 단계만 움직인다. 상향 후보가 아닌 결정은 연속 후보 수를 0 으로 되돌린다. 근거 부족·계산 실패도 "연속"을 끊는 쪽으로 본다(근거 없이 상향되지
 * 않게). 같은 입력과 같은 rules 면 같은 결과를 낸다.
 */
public final class FollowUpDifficultyPolicy {

    private final FollowUpDifficultyRules rules;

    public FollowUpDifficultyPolicy(FollowUpDifficultyRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    public FollowUpDifficultyDecision decide(
            ScenarioLevel currentLevel,
            List<GoalOutcome> previousRoleplayGoals,
            int previousConsecutiveCandidates) {
        Objects.requireNonNull(currentLevel, "currentLevel must not be null");
        if (!computable(previousRoleplayGoals, previousConsecutiveCandidates)) {
            return keep(currentLevel, Reason.CALCULATION_FAILED, new GoalScoreRate(0, 0), 0);
        }

        GoalScoreRate rate = GoalScoreRate.of(previousRoleplayGoals);
        List<GoalOutcome> valid =
                previousRoleplayGoals.stream().filter(GoalOutcome::valid).toList();
        if (valid.size() < rules.minimumValidGoals()) {
            return keep(currentLevel, Reason.INSUFFICIENT_VALID_GOALS, rate, 0);
        }

        long answerOrMissed = valid.stream().filter(GoalOutcome::neededAnswerOrMissed).count();
        if (!rate.atLeastPercent(rules.lowerBelowPercent())) {
            return lower(currentLevel, Reason.SCORE_BELOW_THRESHOLD, rate);
        }
        if (answerOrMissed * 2 >= valid.size()) {
            return lower(currentLevel, Reason.ANSWER_OR_MISSED_MAJORITY, rate);
        }
        if (!rate.atLeastPercent(rules.upgradeCandidateAtLeastPercent())) {
            return keep(currentLevel, Reason.SCORE_IN_KEEP_RANGE, rate, 0);
        }
        if (answerOrMissed > 0) {
            return keep(currentLevel, Reason.HIGH_SCORE_WITH_ANSWER_OR_MISSED, rate, 0);
        }

        Integer fastUpgrade = rules.fastUpgradeAtLeastPercent();
        if (fastUpgrade != null && rate.atLeastPercent(fastUpgrade)) {
            return raise(currentLevel, Reason.FAST_UPGRADE, rate);
        }
        int candidates = previousConsecutiveCandidates + 1;
        if (candidates >= rules.requiredConsecutiveCandidates()) {
            return raise(currentLevel, Reason.UPGRADE_CANDIDATE_CONFIRMED, rate);
        }
        return keep(currentLevel, Reason.UPGRADE_CANDIDATE_PENDING, rate, candidates);
    }

    private static boolean computable(List<GoalOutcome> goals, int previousCandidates) {
        if (goals == null || previousCandidates < 0) return false;
        Set<Object> goalIds = new HashSet<>();
        for (GoalOutcome goal : goals) {
            if (goal == null || !goalIds.add(goal.goalId())) return false;
        }
        return true;
    }

    private FollowUpDifficultyDecision lower(
            ScenarioLevel current, Reason reason, GoalScoreRate rate) {
        if (current == ScenarioLevel.L1) return keep(current, Reason.ALREADY_LOWEST, rate, 0);
        return decision(current, current.lower(), Direction.LOWER, reason, rate, 0);
    }

    private FollowUpDifficultyDecision raise(
            ScenarioLevel current, Reason reason, GoalScoreRate rate) {
        if (current == ScenarioLevel.L3) return keep(current, Reason.ALREADY_HIGHEST, rate, 0);
        return decision(current, current.higher(), Direction.RAISE, reason, rate, 0);
    }

    private FollowUpDifficultyDecision keep(
            ScenarioLevel current, Reason reason, GoalScoreRate rate, int candidates) {
        return decision(current, current, Direction.KEEP, reason, rate, candidates);
    }

    private FollowUpDifficultyDecision decision(
            ScenarioLevel previous,
            ScenarioLevel next,
            Direction direction,
            Reason reason,
            GoalScoreRate rate,
            int candidates) {
        return new FollowUpDifficultyDecision(
                previous, next, direction, reason, rate, candidates, rules.version());
    }
}
