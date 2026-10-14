package com.neuringo.neuringobe.auth.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 강사의 민감 조회 기록. 아동 실명이나 학습 결과가 보이는 조회에 성공했을 때 서버 로그에 한 줄을 남긴다.
 *
 * <p>남기는 값은 강사 ID, 대상 종류, 대상 ID 뿐이다. 실명·이메일·발화·응답 내용은 넣지 않는다. 보관 기간은 일반 로그와 같다. 요청 단위 traceId 는 아직
 * 없고, 그 작업에서 로그 형식에 traceId 를 넣으면 이 줄에도 함께 찍힌다.
 *
 * <p>아동이 자기 활동을 보는 요청은 강사의 조회가 아니라 남기지 않는다.
 */
@Component
public class SensitiveReadLog {

    private static final Logger log = LoggerFactory.getLogger(SensitiveReadLog.class);

    public enum Target {
        CHILD,
        CLASSROOM_CHILDREN,
        CHILD_ACTIVITIES,
        CHILD_GOALS,
        GOAL,
        ACTIVITY,
        QUIZ_RESULT,
        QUIZ_ATTEMPT
    }

    public void record(UUID instructorId, Target target, UUID targetId) {
        log.info(
                "SENSITIVE_READ instructorId={} target={} targetId={}",
                instructorId,
                target,
                targetId);
    }

    /** 아동 입장 세션과 강사가 함께 부르는 API 용. 강사일 때만 남긴다. */
    public void recordIfInstructor(Authentication authentication, Target target, UUID targetId) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            record(user.userId(), target, targetId);
        }
    }
}
