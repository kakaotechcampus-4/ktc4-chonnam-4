package com.neuringo.neuringobe.scenario.domain;

public enum ScenarioStatus {
    /** 승인 전 초안. 아동에게 붙지 않는다. */
    DRAFT,
    /** 새 배정에 쓸 수 있다. */
    APPROVED,
    /** 고친 판으로 대체되어 새 배정에서 빠진다. 이미 붙은 활동은 계속 가리킨다. */
    RETIRED
}
