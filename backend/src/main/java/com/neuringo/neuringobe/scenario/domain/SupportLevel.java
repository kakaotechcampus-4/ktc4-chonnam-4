package com.neuringo.neuringobe.scenario.domain;

/**
 * 목표당 지원 단계(06 확정 정책표 "S0→S1→S2→S3"). S0 열린 질문, S1 단서, S2 두 선택지, S3 답 알려 주기.
 *
 * <p>S3 에서 도달한 목표는 정답을 제공받은 것이라 독립 달성과 구분한다(03 VS-012).
 */
public enum SupportLevel {
    S0,
    S1,
    S2,
    S3;

    /** 이 단계에서 S3 까지 남은 단계 수. 점수 정규화의 분모다. */
    int stepsToFullSupport() {
        return S3.ordinal() - ordinal();
    }

    /** start 에서 시작해 이 단계까지 더 들어간 지원 단계 수. */
    int extraStepsFrom(SupportLevel start) {
        return ordinal() - start.ordinal();
    }
}
