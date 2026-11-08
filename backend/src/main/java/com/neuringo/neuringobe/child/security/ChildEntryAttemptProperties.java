package com.neuringo.neuringobe.child.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 아동 입장 틀린 코드 횟수 제한 값. 비우면 기본값(10분 안에 30번 틀리면 10분 막기, IP 1만 개까지 기억).
 *
 * @param maxFailures 한 IP 가 {@code window} 안에 틀려도 되는 횟수. 이 횟수째 틀리면 막는다
 * @param window 첫 실패부터 세는 시간. 지나도록 한도에 닿지 않으면 처음부터 다시 센다
 * @param blockFor 한도에 닿은 IP 를 막는 시간(429 의 Retry-After)
 * @param maxTrackedClients 메모리에 기억하는 IP 수 상한
 */
@ConfigurationProperties("neuringo.child-access.attempt-limit")
public record ChildEntryAttemptProperties(
        Integer maxFailures, Duration window, Duration blockFor, Integer maxTrackedClients) {

    public ChildEntryAttemptProperties {
        maxFailures = maxFailures == null ? 30 : maxFailures;
        window = window == null ? Duration.ofMinutes(10) : window;
        blockFor = blockFor == null ? Duration.ofMinutes(10) : blockFor;
        maxTrackedClients = maxTrackedClients == null ? 10_000 : maxTrackedClients;
        if (maxFailures < 1) {
            throw new IllegalArgumentException("입장 코드 실패 한도는 1 이상이어야 합니다.");
        }
        if (window.isNegative() || window.isZero() || blockFor.isNegative() || blockFor.isZero()) {
            throw new IllegalArgumentException("입장 코드 실패를 세는 시간과 막는 시간은 0보다 커야 합니다.");
        }
        if (maxTrackedClients < 1) {
            throw new IllegalArgumentException("기억하는 IP 수는 1 이상이어야 합니다.");
        }
    }
}
