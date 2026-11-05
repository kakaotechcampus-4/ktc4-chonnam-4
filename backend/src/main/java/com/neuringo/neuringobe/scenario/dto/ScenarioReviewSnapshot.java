package com.neuringo.neuringobe.scenario.dto;

import java.util.UUID;

/** 현재 한 후보만 전달한다. 후보가 null이면 준비 조정 계층에서 신규 생성을 연결해야 한다. */
public record ScenarioReviewSnapshot(
        UUID reviewId,
        UUID activityId,
        long revision,
        Integer currentPosition,
        ScenarioVersionRef candidate,
        int candidateCount) {}
