package com.neuringo.neuringobe.scenario.domain;

/** 시나리오 복잡도 L1~L3(03 VS-007·VS-016). 한 활동으로 한 단계까지만 움직이고, 양 끝에서는 더 내려가거나 올라가지 않는다. */
public enum ScenarioLevel {
    L1,
    L2,
    L3;

    public ScenarioLevel lower() {
        return this == L1 ? L1 : values()[ordinal() - 1];
    }

    public ScenarioLevel higher() {
        return this == L3 ? L3 : values()[ordinal() + 1];
    }

    /** 이 레벨 역할극의 시작 지원 단계. 최초 난이도(quiz InitialDifficultyPolicy)의 L1·S2, L2·S1, L3·S0 짝과 같다. */
    public SupportLevel startSupport() {
        return switch (this) {
            case L1 -> SupportLevel.S2;
            case L2 -> SupportLevel.S1;
            case L3 -> SupportLevel.S0;
        };
    }
}
