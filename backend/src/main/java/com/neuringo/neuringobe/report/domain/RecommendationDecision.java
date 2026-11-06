package com.neuringo.neuringobe.report.domain;

/** 강사의 추천 결정(API-REC-05). 채택·고쳐서 채택·거절은 확정이고, 보류만 나중에 바꿀 수 있다. */
public enum RecommendationDecision {
    ACCEPT,
    MODIFIED_ACCEPT,
    DEFER,
    REJECT;

    /** recommendation.status 에 남기는 값. */
    public String status() {
        return switch (this) {
            case ACCEPT -> "ACCEPTED";
            case MODIFIED_ACCEPT -> "MODIFIED_ACCEPTED";
            case DEFER -> "DEFERRED";
            case REJECT -> "REJECTED";
        };
    }
}
