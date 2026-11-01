package com.neuringo.neuringobe.scenario.dto;

/** 재사용 후보 하나와 다음 후보가 있는지. 후보가 없으면 scenario 는 null 이다. */
public record ScenarioCandidateResponse(ScenarioResponse scenario, boolean hasNext) {}
