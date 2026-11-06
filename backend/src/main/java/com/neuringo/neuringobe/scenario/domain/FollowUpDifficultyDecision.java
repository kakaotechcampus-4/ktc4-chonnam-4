package com.neuringo.neuringobe.scenario.domain;

import java.util.Objects;

/**
 * 후속 난이도 결정 결과(03 VS-016 "결과, 점수율, 연속 후보 수와 정책 버전이 기록된다").
 *
 * @param direction 실제로 바뀐 방향. L1 에서 하향·L3 에서 상향으로 판정되면 KEEP 이고 reason 이 그 사정을 남긴다.
 * @param consecutiveUpgradeCandidates 이번 결정까지 이어진 상향 후보 수. 다음 결정의 입력이다.
 */
public record FollowUpDifficultyDecision(
        ScenarioLevel previousLevel,
        ScenarioLevel nextLevel,
        Direction direction,
        Reason reason,
        GoalScoreRate scoreRate,
        int consecutiveUpgradeCandidates,
        String policyVersion) {

    public enum Direction {
        LOWER,
        KEEP,
        RAISE
    }

    public enum Reason {
        SCORE_BELOW_THRESHOLD,
        ANSWER_OR_MISSED_MAJORITY,
        SCORE_IN_KEEP_RANGE,
        HIGH_SCORE_WITH_ANSWER_OR_MISSED,
        UPGRADE_CANDIDATE_PENDING,
        UPGRADE_CANDIDATE_CONFIRMED,
        FAST_UPGRADE,
        ALREADY_LOWEST,
        ALREADY_HIGHEST,
        INSUFFICIENT_VALID_GOALS,
        CALCULATION_FAILED
    }

    public FollowUpDifficultyDecision {
        Objects.requireNonNull(previousLevel, "previousLevel must not be null");
        Objects.requireNonNull(nextLevel, "nextLevel must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(scoreRate, "scoreRate must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        if (consecutiveUpgradeCandidates < 0) {
            throw new IllegalArgumentException("consecutiveUpgradeCandidates must not be negative");
        }
        if (Math.abs(nextLevel.ordinal() - previousLevel.ordinal()) > 1) {
            throw new IllegalArgumentException("difficulty must not move more than one level");
        }
    }
}
