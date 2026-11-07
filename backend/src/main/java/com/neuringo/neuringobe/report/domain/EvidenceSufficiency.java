package com.neuringo.neuringobe.report.domain;

/** 추천 근거가 충분한지(03 VS-015 "근거 충분성이 표시된다", DEC-013 "근거 부족 표시"). */
public enum EvidenceSufficiency {
    /** 유효 목표가 기준 수 이상이고 바탕 목표에 관찰 증거가 있다 */
    SUFFICIENT,
    /** 유효 목표가 적거나 바탕 목표에 관찰 증거가 없다 */
    LIMITED,
    /** 유효 목표가 없다(기술 오류·중단만 있었다) */
    INSUFFICIENT
}
