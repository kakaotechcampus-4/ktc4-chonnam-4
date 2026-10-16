package com.neuringo.neuringobe.auth.security;

import com.neuringo.neuringobe.common.ApiException;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 강사의 민감 조회 기록. 아동 실명이나 학습 결과가 보이는 조회를 강사가 시도하면 서버 로그에 한 줄을 남긴다.
 *
 * <p>성공한 조회는 {@code outcome=ALLOWED}, 403·404 로 막힌 조회는 {@code outcome=BLOCKED} 와 오류 코드를 남긴다. 남의 아동
 * ID 를 넣어 보는 시도는 서버가 없는 자원과 같은 404 로 숨기므로, 감사에서 보려면 막힌 조회도 남아야 한다. 같은 404 라도 자기 아동의 아직 없는 풀이({@code
 * ATTEMPT_NOT_FOUND})처럼 정상 흐름인 것이 있어 코드로 구분한다. 그 밖의 오류(입력 검증 등)는 조회가 아니라 남기지 않는다.
 *
 * <p>남기는 값은 강사 ID, 대상 종류, 대상 ID, 결과뿐이다. 실명·이메일·발화·응답 내용은 넣지 않는다. 보관 기간은 아직 일반 로그와 같다. 요청 단위 traceId
 * 는 아직 없고, 그 작업에서 로그 형식에 traceId 를 넣으면 이 줄에도 함께 찍힌다.
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

    /** 강사 조회를 실행하고 결과를 남긴다. 막힌 조회는 남긴 뒤 같은 예외를 다시 던진다. */
    public <T> T read(UUID instructorId, Target target, UUID targetId, Supplier<T> query) {
        T result;
        try {
            result = query.get();
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.FORBIDDEN || e.getStatus() == HttpStatus.NOT_FOUND) {
                log.info(
                        "SENSITIVE_READ instructorId={} target={} targetId={} outcome=BLOCKED"
                                + " status={} code={}",
                        instructorId,
                        target,
                        targetId,
                        e.getStatus().value(),
                        e.getCode());
            }
            throw e;
        }
        log.info(
                "SENSITIVE_READ instructorId={} target={} targetId={} outcome=ALLOWED",
                instructorId,
                target,
                targetId);
        return result;
    }

    /** 아동 입장 세션과 강사가 함께 부르는 API 용. 강사일 때만 남긴다. */
    public <T> T read(
            Authentication authentication, Target target, UUID targetId, Supplier<T> query) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return read(user.userId(), target, targetId, query);
        }
        return query.get();
    }
}
