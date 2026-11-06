package com.neuringo.neuringobe.scenario.config;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyRules;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 다음 역할극 난이도의 팀 결정 대기 값. 기본값은 06 확정 정책표(상향 후보 80%, 빠른 상향 끔) 그대로다. 값을 바꾸면 정책 버전 뒤에 바뀐 값을 붙여, 결정 행만
 * 보고도 어떤 기준으로 정했는지 알 수 있게 한다.
 *
 * @param upgradeThresholdPercent 상향 후보 기준(%). null 이면 80
 * @param fastUpgradePercent 한 번에 올리는 빠른 상향 기준(%, DEC-014). null 이면 끈다
 */
@ConfigurationProperties(prefix = "neuringo.report.difficulty")
public record FollowUpDifficultyProperties(
        Integer upgradeThresholdPercent, Integer fastUpgradePercent) {

    public FollowUpDifficultyRules rules() {
        FollowUpDifficultyRules base = FollowUpDifficultyRules.V1;
        int upgrade =
                upgradeThresholdPercent == null
                        ? base.upgradeCandidateAtLeastPercent()
                        : upgradeThresholdPercent;
        if (upgrade == base.upgradeCandidateAtLeastPercent() && fastUpgradePercent == null) {
            return base;
        }
        String version =
                base.version()
                        + "+UP"
                        + upgrade
                        + (fastUpgradePercent == null ? "" : "+FAST" + fastUpgradePercent);
        return new FollowUpDifficultyRules(
                version,
                base.lowerBelowPercent(),
                upgrade,
                base.requiredConsecutiveCandidates(),
                base.minimumValidGoals(),
                fastUpgradePercent);
    }
}
