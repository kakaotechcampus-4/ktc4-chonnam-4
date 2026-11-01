package com.neuringo.neuringobe.scenario.domain;

public enum ScenarioStatus {
    /** 승인 전 초안. 아동에게 붙지 않고, 이 상태일 때만 내용을 고칠 수 있다. */
    DRAFT,
    /** 강사가 고쳐 오라고 판정했다. 다시 만든 결과는 새 초안 행이 된다. */
    REVISION_REQUIRED,
    /** 강사가 쓰지 않기로 판정했다. 다시 승인되거나 아동에게 붙지 않는다. */
    REJECTED,
    /** 재사용 후보가 될 수 있다. 고친 판이 승인돼도 그대로 남는다. */
    APPROVED
}
