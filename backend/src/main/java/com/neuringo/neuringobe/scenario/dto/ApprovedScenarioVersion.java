package com.neuringo.neuringobe.scenario.dto;

import java.util.UUID;

/** 조회 시점에 승인 상태와 재사용 조건을 확인한 버전 참조다. */
public record ApprovedScenarioVersion(UUID scenarioId, int version) {}
