package com.neuringo.neuringobe.scenario.dto;

import java.util.UUID;

/** 검토 대상의 식별자다. 승인 여부는 참조만으로 보장하지 않는다. */
public record ScenarioVersionRef(UUID scenarioId, int version) {}
