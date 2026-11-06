package com.neuringo.neuringobe.scenario.domain;

import java.util.Objects;

/**
 * 후속 난이도 정책의 운영값(06 "현재 문서 기준으로 바로 확정 가능한 정책"). 값이 바뀌면 version 도 바꾼다. 결정마다 version 을 남겨서 나중에 같은
 * 입력으로 같은 결과를 다시 낼 수 있게 한다.
 *
 * @param fastUpgradeAtLeastPercent 90% 빠른 상향 예외(DEC-014). null 이면 끈다. MVP 기본값은 끈 상태다.
 */
public record FollowUpDifficultyRules(
        String version,
        int lowerBelowPercent,
        int upgradeCandidateAtLeastPercent,
        int requiredConsecutiveCandidates,
        int minimumValidGoals,
        Integer fastUpgradeAtLeastPercent) {

    public static final FollowUpDifficultyRules V1 =
            new FollowUpDifficultyRules("FOLLOW_UP_ROLEPLAY_V1", 50, 80, 2, 2, null);

    public FollowUpDifficultyRules {
        Objects.requireNonNull(version, "version must not be null");
        if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        if (lowerBelowPercent < 0 || lowerBelowPercent > upgradeCandidateAtLeastPercent) {
            throw new IllegalArgumentException("lower threshold must not exceed upgrade threshold");
        }
        if (upgradeCandidateAtLeastPercent > 100) {
            throw new IllegalArgumentException("upgrade threshold must not exceed 100");
        }
        if (requiredConsecutiveCandidates < 1 || minimumValidGoals < 1) {
            throw new IllegalArgumentException("counts must be positive");
        }
        if (fastUpgradeAtLeastPercent != null
                && (fastUpgradeAtLeastPercent < upgradeCandidateAtLeastPercent
                        || fastUpgradeAtLeastPercent > 100)) {
            throw new IllegalArgumentException("fast upgrade threshold is out of range");
        }
    }
}
