package com.neuringo.neuringobe.scenario.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficultyRules;
import org.junit.jupiter.api.Test;

class FollowUpDifficultyPropertiesTest {

    @Test
    void defaultsKeepTheConfirmedTeamPolicy() {
        assertThat(new FollowUpDifficultyProperties(null, null).rules())
                .isSameAs(FollowUpDifficultyRules.V1);
        assertThat(new FollowUpDifficultyProperties(80, null).rules())
                .isSameAs(FollowUpDifficultyRules.V1);
    }

    @Test
    void changedThresholdsAreVisibleInThePolicyVersion() {
        var rules = new FollowUpDifficultyProperties(70, 90).rules();

        assertThat(rules.upgradeCandidateAtLeastPercent()).isEqualTo(70);
        assertThat(rules.fastUpgradeAtLeastPercent()).isEqualTo(90);
        assertThat(rules.version()).isEqualTo("FOLLOW_UP_ROLEPLAY_V1+UP70+FAST90");
    }
}
