package com.neuringo.neuringobe.roleplay.service;

import java.util.UUID;

/**
 * 역할극으로 활동이 처음 완료됐다는 사실. 완료 트랜잭션 안에서 발행하고, 받는 쪽은 커밋 뒤에 처리한다. 퀴즈의 FirstQuizCompleted 처럼 발행하는 패키지에 두어
 * report → roleplay 방향으로만 의존한다.
 */
public record RoleplayActivityCompleted(UUID activityId, UUID childId, UUID sessionId) {}
