package com.neuringo.neuringobe.quiz.service;

import java.util.UUID;

/** 최초 난이도 저장·미연결 복구 알림. 소비자는 AFTER_COMMIT에서 저장 근거를 재확인하고 별도 트랜잭션으로 처리한다. */
public record FirstQuizCompleted(UUID activityId, UUID childId, String scenarioLevel) {}
