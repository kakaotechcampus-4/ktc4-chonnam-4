package com.neuringo.neuringobe.activity.domain;

import java.util.Objects;

public record ActivityReadiness(
        ActivityNextAction nextAction, ActivityBlockedReason blockedReason) {
    public ActivityReadiness {
        Objects.requireNonNull(nextAction);
        if ((nextAction == ActivityNextAction.BLOCKED) != (blockedReason != null)) {
            throw new IllegalArgumentException("실행 차단 결과에만 차단 사유가 필요합니다.");
        }
    }

    public static ActivityReadiness available(ActivityNextAction action) {
        return new ActivityReadiness(action, null);
    }

    public static ActivityReadiness blocked(ActivityBlockedReason reason) {
        return new ActivityReadiness(ActivityNextAction.BLOCKED, Objects.requireNonNull(reason));
    }
}
