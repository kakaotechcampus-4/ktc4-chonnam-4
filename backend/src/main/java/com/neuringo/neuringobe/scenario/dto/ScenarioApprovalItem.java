package com.neuringo.neuringobe.scenario.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 승인 대기함의 한 줄. 시나리오를 기다리는 최초 결합 활동 하나다.
 *
 * <p>state 는 고치는 중인 초안이 있으면 DRAFT_IN_PROGRESS(draftScenarioId·draftVersion 이 있음), 아니면 재사용 후보가 있으면
 * CANDIDATE_AVAILABLE, 둘 다 없으면 NO_CANDIDATE 다. 후보가 몇 개인지, 다른 아동이 언제 썼는지는 담지 않는다.
 */
public record ScenarioApprovalItem(
        UUID activityId,
        UUID childId,
        String childName,
        UUID classId,
        String className,
        UUID goalId,
        String goalTitle,
        String scenarioLevel,
        Instant waitingSince,
        String state,
        UUID draftScenarioId,
        Integer draftVersion) {}
