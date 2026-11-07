package com.neuringo.neuringobe.report.domain;

import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import java.util.Objects;

/**
 * 리포트에 함께 보여 줄 난이도 결정 근거(03 VS-014 "난이도 결정 근거와 정책 버전을 확인할 수 있다").
 *
 * @param reasonCode 최초 난이도는 정답률 구간, 후속 난이도는 FollowUpDifficultyDecision.Reason 이름
 */
public record DifficultyBasis(
        DecisionType decisionType,
        ScenarioLevel scenarioLevel,
        String reasonCode,
        String policyVersion) {

    public enum DecisionType {
        INITIAL,
        FOLLOW_UP
    }

    public DifficultyBasis {
        Objects.requireNonNull(decisionType, "decisionType must not be null");
        Objects.requireNonNull(scenarioLevel, "scenarioLevel must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
    }
}
