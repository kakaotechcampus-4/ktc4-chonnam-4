package com.neuringo.neuringobe.scenario.dto;

/** 조회 중 취소 여부를 저장 직전에 확인할 서버 내부 시작 정보다. */
public record ScenarioReviewStartAttempt(ScenarioReviewSnapshot snapshot, long epoch) {}
