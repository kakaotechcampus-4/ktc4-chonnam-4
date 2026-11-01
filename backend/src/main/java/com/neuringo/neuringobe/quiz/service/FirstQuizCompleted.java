package com.neuringo.neuringobe.quiz.service;

import java.util.UUID;

/**
 * 최초 난이도 저장·결과 재조회 알림. 시나리오는 담당 강사가 재사용 후보를 승인해야 붙으므로 지금은 받는 곳이 없다. 시나리오 생성을 붙일 때 이 알림을
 * AFTER_COMMIT에서 받아 저장 근거를 재확인하고 별도 트랜잭션으로 처리한다.
 */
public record FirstQuizCompleted(UUID activityId, UUID childId, String scenarioLevel) {}
