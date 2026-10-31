package com.neuringo.neuringobe.activity.dto;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;

/** 활동 완료 status 전용 조회 응답. 화면은 COMPLETED 일 때 스탬프를 보여 준다. 조회는 완료·지급을 다시 실행하지 않는다. */
public record ActivityStatusResponse(ActivityStatus status) {}
