package com.neuringo.neuringobe.child.security;

import com.neuringo.neuringobe.common.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 아동 입장 코드(4자리)를 무차별로 찍어 보는 것을 늦춘다. 접속 IP 별로 틀린 코드 횟수만 센다. 맞은 입장은 세지 않으므로 한 학교가 IP 하나(NAT)로 함께 들어와도
 * 정상 입장 때문에 막히지 않는다.
 *
 * <p>첫 실패부터 {@code window} 안에 {@code maxFailures} 번째로 틀리면 그 IP 의 입장을 {@code blockFor} 동안 429 로
 * 막는다(맞는 코드도). 막힌 동안의 요청은 코드 확인까지 가지 않으므로 다시 세지 않고, 막는 시간도 늘리지 않는다. {@code window} 가 지나도록 한도에 닿지
 * 않았거나 막는 시간이 끝나면 처음부터 다시 센다.
 *
 * <p>서버 한 대의 메모리에만 둔다. 재시작·배포 때 지워지고, 서버를 여러 대로 늘리면 공유 저장소로 옮겨야 한다. 기억하는 IP 가 {@code
 * maxTrackedClients} 를 넘으면 기한이 지난 것부터 지우고, 그래도 넘치면 가장 먼저 세기 시작한 것을 지운다. 앞단 nginx 의 IP 별 요청 수
 * 제한(frontend/nginx.conf)과 따로 돈다.
 */
public class ChildEntryAttemptLimiter {

    public static final String ERROR_CODE = "ACCESS_CODE_ATTEMPTS_EXCEEDED";

    private static final Logger log = LoggerFactory.getLogger(ChildEntryAttemptLimiter.class);

    private final ChildEntryAttemptProperties properties;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public ChildEntryAttemptLimiter(ChildEntryAttemptProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 막혀 있는 IP 면 429(Retry-After 는 남은 막는 시간)를 던진다. */
    public void checkAllowed(String client) {
        Attempts current = attempts.get(client);
        if (current == null || current.blockedUntil() == null) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(current.blockedUntil())) {
            throw new TooManyRequestsException(
                    ERROR_CODE,
                    "입장 코드를 여러 번 틀렸습니다. 잠시 뒤에 다시 입력해 주세요.",
                    Duration.between(now, current.blockedUntil()));
        }
        attempts.remove(client, current);
    }

    /** 틀린 코드 한 번. 한도에 닿으면 그때부터 막는다. */
    public void recordFailure(String client) {
        Instant now = clock.instant();
        Attempts updated = attempts.compute(client, (key, previous) -> next(previous, now));
        if (updated.failures() == properties.maxFailures()) {
            log.warn(
                    "아동 입장 코드를 {}분 안에 {}번 틀린 접속 IP 를 {}초 동안 막습니다. client={}",
                    properties.window().toMinutes(),
                    updated.failures(),
                    properties.blockFor().toSeconds(),
                    client);
        }
        if (attempts.size() > properties.maxTrackedClients()) {
            evict(now);
        }
    }

    int trackedClients() {
        return attempts.size();
    }

    private Attempts next(Attempts previous, Instant now) {
        if (previous == null || previous.isOver(now, properties.window())) {
            previous = new Attempts(now, 0, null);
        }
        int failures = previous.failures() + 1;
        Instant blockedUntil = previous.blockedUntil();
        if (blockedUntil == null && failures >= properties.maxFailures()) {
            blockedUntil = now.plus(properties.blockFor());
        }
        return new Attempts(previous.windowStart(), failures, blockedUntil);
    }

    private void evict(Instant now) {
        attempts.entrySet().removeIf(entry -> entry.getValue().isOver(now, properties.window()));
        while (attempts.size() > properties.maxTrackedClients()) {
            attempts.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().windowStart()))
                    .ifPresent(oldest -> attempts.remove(oldest.getKey(), oldest.getValue()));
        }
    }

    /**
     * 한 IP 의 실패 기록.
     *
     * @param windowStart 이번에 세기 시작한 첫 실패 시각
     * @param failures 그 뒤로 틀린 횟수
     * @param blockedUntil 막힌 경우 풀리는 시각, 아니면 null
     */
    private record Attempts(Instant windowStart, int failures, Instant blockedUntil) {

        /** 이 기록이 끝났는지. 막혔으면 막는 시간이, 아니면 세는 시간이 지났을 때다. */
        boolean isOver(Instant now, Duration window) {
            Instant end = blockedUntil != null ? blockedUntil : windowStart.plus(window);
            return !now.isBefore(end);
        }
    }
}
