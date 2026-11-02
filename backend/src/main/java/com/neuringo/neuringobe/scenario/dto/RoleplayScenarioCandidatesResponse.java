package com.neuringo.neuringobe.scenario.dto;

import java.util.List;
import java.util.UUID;

/**
 * 역할극 단독 배정의 시나리오 후보. 레벨·시작 지원은 지금 배정하면 활동에 고정될 다음 역할극 난이도다.
 *
 * @param items 마지막으로 쓰인 지 오래된 것(안 쓰인 것이 맨 앞) → 최근 승인본 순
 */
public record RoleplayScenarioCandidatesResponse(
        UUID childId,
        String scenarioLevel,
        String initialSupportLevel,
        List<RoleplayScenarioCandidate> items) {}
