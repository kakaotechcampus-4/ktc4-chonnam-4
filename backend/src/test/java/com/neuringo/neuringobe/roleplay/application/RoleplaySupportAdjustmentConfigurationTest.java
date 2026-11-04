package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.roleplay.config.RoleplayProgressConfiguration;
import com.neuringo.neuringobe.roleplay.config.RoleplaySupportAdjustmentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RoleplaySupportAdjustmentConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RoleplayProgressConfiguration.class);

    @Test
    void absentConfigurationStillRequiresAnExplicitPolicy() {
        runner.run(
                context -> {
                    var rules = context.getBean(RoleplaySupportAdjustmentProperties.class);
                    assertThat(rules.configured()).isFalse();
                    assertThat(rules.rules()).isNull();
                });
    }

    @Test
    void allExplicitSyntheticSettingsAreBoundWithoutChangingThem() {
        runner.withPropertyValues(
                        "roleplay.support-adjustment.required-independent-goals=3",
                        "roleplay.support-adjustment.in-progress-handling=KEEP_SEQUENCE",
                        "roleplay.support-adjustment.policy-version=synthetic-support-v1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var rules =
                                    context.getBean(RoleplaySupportAdjustmentProperties.class)
                                            .rules();
                            assertThat(rules.requiredIndependentGoals()).isEqualTo(3);
                            assertThat(rules.inProgressHandling())
                                    .isEqualTo(
                                            RoleplaySupportAdjustmentPolicy.InProgressHandling
                                                    .KEEP_SEQUENCE);
                            assertThat(rules.policyVersion()).isEqualTo("synthetic-support-v1");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "handling"})
    void approvedVersionCannotSilentlyChangeItsThresholdOrInterruptionRule(String changed) {
        runner.withPropertyValues(
                        "roleplay.support-adjustment.required-independent-goals="
                                + (changed.equals("count") ? "3" : "2"),
                        "roleplay.support-adjustment.in-progress-handling="
                                + (changed.equals("handling") ? "KEEP_SEQUENCE" : "RESET_SEQUENCE"),
                        "roleplay.support-adjustment.policy-version=SUPPORT_DECREASE_V1")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "countOnly",
                "handlingOnly",
                "versionOnly",
                "zero",
                "negative",
                "blankVersion"
            })
    void incompleteOrInvalidPoliciesFailAtStartup(String change) {
        var values =
                switch (change) {
                    case "countOnly" ->
                            new String[] {
                                "roleplay.support-adjustment.required-independent-goals=2"
                            };
                    case "handlingOnly" ->
                            new String[] {
                                "roleplay.support-adjustment.in-progress-handling=RESET_SEQUENCE"
                            };
                    case "versionOnly" ->
                            new String[] {
                                "roleplay.support-adjustment.policy-version=synthetic-v1"
                            };
                    default ->
                            new String[] {
                                "roleplay.support-adjustment.required-independent-goals="
                                        + (change.equals("zero")
                                                ? "0"
                                                : change.equals("negative") ? "-1" : "2"),
                                "roleplay.support-adjustment.in-progress-handling=RESET_SEQUENCE",
                                "roleplay.support-adjustment.policy-version="
                                        + (change.equals("blankVersion") ? "" : "synthetic-v1")
                            };
                };
        runner.withPropertyValues(values).run(context -> assertThat(context).hasFailed());
    }
}
