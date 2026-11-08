package com.neuringo.neuringobe.child.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.common.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class ChildEntryAttemptLimiterTest {

    private static final String SCHOOL = "203.0.113.10";
    private static final String OTHER = "203.0.113.20";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T01:00:00Z"));

    private ChildEntryAttemptLimiter limiter(int maxFailures, int maxTrackedClients) {
        return new ChildEntryAttemptLimiter(
                new ChildEntryAttemptProperties(
                        maxFailures,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(10),
                        maxTrackedClients),
                clock);
    }

    @Test
    void blocksOnlyAfterTheLimitIsReachedAndReportsRemainingSeconds() {
        ChildEntryAttemptLimiter limiter = limiter(30, 100);
        for (int i = 0; i < 29; i++) {
            limiter.recordFailure(SCHOOL);
        }
        assertThatCode(() -> limiter.checkAllowed(SCHOOL)).doesNotThrowAnyException();

        limiter.recordFailure(SCHOOL);

        assertThatThrownBy(() -> limiter.checkAllowed(SCHOOL))
                .isInstanceOfSatisfying(
                        TooManyRequestsException.class,
                        ex -> {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                            assertThat(ex.getCode()).isEqualTo("ACCESS_CODE_ATTEMPTS_EXCEEDED");
                            assertThat(ex.getRetryAfterSeconds()).isEqualTo(600);
                            assertThat(ex.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                                    .isEqualTo("600");
                        });
        assertThatCode(() -> limiter.checkAllowed(OTHER)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterCountsDownAndRoundsUp() {
        ChildEntryAttemptLimiter limiter = limiter(1, 100);
        limiter.recordFailure(SCHOOL);

        clock.advance(Duration.ofMinutes(9).plusSeconds(59).plusMillis(500));

        assertThatThrownBy(() -> limiter.checkAllowed(SCHOOL))
                .isInstanceOfSatisfying(
                        TooManyRequestsException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void failuresWhileBlockedDoNotExtendTheBlock() {
        ChildEntryAttemptLimiter limiter = limiter(2, 100);
        limiter.recordFailure(SCHOOL);
        limiter.recordFailure(SCHOOL);
        clock.advance(Duration.ofMinutes(5));
        // 막히기 직전에 코드 확인까지 간 요청이 늦게 실패로 돌아온 경우
        limiter.recordFailure(SCHOOL);

        clock.advance(Duration.ofMinutes(5));

        assertThatCode(() -> limiter.checkAllowed(SCHOOL)).doesNotThrowAnyException();
    }

    @Test
    void startsCountingAgainAfterTheBlockEnds() {
        ChildEntryAttemptLimiter limiter = limiter(3, 100);
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(SCHOOL);
        }
        clock.advance(Duration.ofMinutes(10));
        assertThatCode(() -> limiter.checkAllowed(SCHOOL)).doesNotThrowAnyException();

        limiter.recordFailure(SCHOOL);
        limiter.recordFailure(SCHOOL);

        assertThatCode(() -> limiter.checkAllowed(SCHOOL)).doesNotThrowAnyException();
    }

    @Test
    void forgetsFailuresWhenTheWindowPassesWithoutReachingTheLimit() {
        ChildEntryAttemptLimiter limiter = limiter(3, 100);
        limiter.recordFailure(SCHOOL);
        limiter.recordFailure(SCHOOL);

        clock.advance(Duration.ofMinutes(10));
        limiter.recordFailure(SCHOOL);
        limiter.recordFailure(SCHOOL);

        assertThatCode(() -> limiter.checkAllowed(SCHOOL)).doesNotThrowAnyException();
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfClientsAndKeepsBlockedOnes() {
        ChildEntryAttemptLimiter limiter = limiter(1, 2);
        limiter.recordFailure(SCHOOL);
        clock.advance(Duration.ofMinutes(10));
        // SCHOOL 의 막는 시간은 끝났다 — 넘치면 먼저 지워진다
        limiter.recordFailure("198.51.100.1");
        clock.advance(Duration.ofSeconds(1));
        limiter.recordFailure("198.51.100.2");

        assertThat(limiter.trackedClients()).isEqualTo(2);
        assertThatThrownBy(() -> limiter.checkAllowed("198.51.100.1"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> limiter.checkAllowed("198.51.100.2"))
                .isInstanceOf(TooManyRequestsException.class);

        clock.advance(Duration.ofSeconds(1));
        limiter.recordFailure("198.51.100.3");

        assertThat(limiter.trackedClients()).isEqualTo(2);
        assertThatCode(() -> limiter.checkAllowed("198.51.100.1")).doesNotThrowAnyException();
    }

    @Test
    void rejectsSettingsThatWouldDisableOrBreakTheLimit() {
        assertThatThrownBy(
                        () ->
                                new ChildEntryAttemptProperties(
                                        0, Duration.ofMinutes(10), Duration.ofMinutes(10), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ChildEntryAttemptProperties(
                                        30, Duration.ZERO, Duration.ofMinutes(10), 10))
                .isInstanceOf(IllegalArgumentException.class);
        ChildEntryAttemptProperties defaults =
                new ChildEntryAttemptProperties(null, null, null, null);
        assertThat(defaults.maxFailures()).isEqualTo(30);
        assertThat(defaults.window()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.blockFor()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.maxTrackedClients()).isEqualTo(10_000);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
