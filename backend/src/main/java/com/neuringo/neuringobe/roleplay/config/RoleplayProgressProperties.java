package com.neuringo.neuringobe.roleplay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roleplay.progress")
public record RoleplayProgressProperties(
        Integer maximumTurnCount, Integer maximumSupportTurnsPerMicroGoal) {
    public RoleplayProgressProperties {
        if ((maximumTurnCount == null) != (maximumSupportTurnsPerMicroGoal == null)
                || (maximumTurnCount != null
                        && (maximumTurnCount < 1 || maximumSupportTurnsPerMicroGoal < 1))) {
            throw new IllegalArgumentException("전체·목표별 턴 제한을 양수로 함께 설정해야 합니다.");
        }
    }

    public boolean configured() {
        return maximumTurnCount != null;
    }
}
