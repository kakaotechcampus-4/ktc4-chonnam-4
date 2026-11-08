package com.neuringo.neuringobe.report.domain;

/** 다음 목표 추천 유형(03 VS-015 "보완·반복·확장·적용 추천"). */
public enum RecommendationType {
    /** 보완: 도달하지 못했거나 답을 알려 줘야 했던 목표를 지원을 더 두고 다시 다룬다 */
    REINFORCE,
    /** 반복: 지원을 더 받고 도달한 목표를 다른 상황으로 한 번 더 한다 */
    REPEAT,
    /** 적용: 지원 없이 도달한 목표를 다른 상황에 써 본다 */
    APPLY,
    /** 확장: 활동 전체를 지원 없이 해냈을 때 관련된 다음 목표로 넘어간다 */
    EXTEND
}
