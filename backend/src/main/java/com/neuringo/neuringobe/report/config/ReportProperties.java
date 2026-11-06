package com.neuringo.neuringobe.report.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 학습 리포트 생성 운영값.
 *
 * @param workers 리포트 생성 스레드 수. LLM 호출이 길어서(평가 p95 45초) 아동 역할극 턴 스레드와 나눈다
 * @param queueCapacity 기다리는 리포트 수 상한. 넘치면 PENDING 으로 두고 재처리 작업이 나중에 넣는다
 * @param staleAfter 이 시간 동안 바뀌지 않은 PENDING 을 멈춘 것으로 보고 다시 넣는다
 * @param maxAttempts 한 리포트를 생성 작업에 넣는 최대 횟수. 넘으면 FAILED(GENERATION_STALLED)
 * @param extendFromLowestLevel L1(S2 시작) 역할극을 만점으로 마쳐도 확장 추천을 낼지(팀 결정 대기, 기본 true = 지금 정책)
 */
@ConfigurationProperties(prefix = "neuringo.report")
public record ReportProperties(
        Integer workers,
        Integer queueCapacity,
        Duration staleAfter,
        Integer maxAttempts,
        Boolean extendFromLowestLevel) {

    public ReportProperties {
        workers = workers == null ? 1 : workers;
        queueCapacity = queueCapacity == null ? 50 : queueCapacity;
        staleAfter = staleAfter == null ? Duration.ofMinutes(10) : staleAfter;
        maxAttempts = maxAttempts == null ? 3 : maxAttempts;
        extendFromLowestLevel = extendFromLowestLevel == null || extendFromLowestLevel;
        if (workers < 1 || queueCapacity < 1 || maxAttempts < 1 || staleAfter.isNegative()) {
            throw new IllegalArgumentException("report generation settings are out of range");
        }
    }
}
