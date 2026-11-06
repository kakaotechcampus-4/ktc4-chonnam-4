package com.neuringo.neuringobe.scenario.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 승인 대기함의 한 줄. 시나리오를 기다리는 최초 결합 활동 하나다.
 *
 * <p>state 는 검토를 시작해 이어 볼 검토가 있으면 IN_REVIEW, 아직 시작하지 않았으면 WAITING 이다. 후보가 몇 개인지, 다른 아동이 언제 썼는지는 담지
 * 않는다.
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
        String state) {}
