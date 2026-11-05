package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.roleplay.application.RoleplaySupportAdjustmentPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roleplay.support-adjustment")
public record RoleplaySupportAdjustmentProperties(
        Integer requiredIndependentGoals,
        RoleplaySupportAdjustmentPolicy.InProgressHandling inProgressHandling,
        String policyVersion) {
    public RoleplaySupportAdjustmentProperties {
        boolean absent =
                requiredIndependentGoals == null
                        && inProgressHandling == null
                        && policyVersion == null;
        if (!absent
                && (requiredIndependentGoals == null
                        || requiredIndependentGoals < 1
                        || inProgressHandling == null
                        || policyVersion == null
                        || policyVersion.isBlank()))
            throw new IllegalArgumentException("지원 감소의 목표 수·진행 중 처리·정책 버전을 함께 명시해야 합니다.");
        if (!absent)
            new RoleplaySupportAdjustmentPolicy.Rules(
                    requiredIndependentGoals, inProgressHandling, policyVersion);
    }

    public boolean configured() {
        return requiredIndependentGoals != null;
    }

    public RoleplaySupportAdjustmentPolicy.Rules rules() {
        return configured()
                ? new RoleplaySupportAdjustmentPolicy.Rules(
                        requiredIndependentGoals, inProgressHandling, policyVersion)
                : null;
    }
}
