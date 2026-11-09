package com.neuringo.neuringobe.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StoredTimeTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // 이번 CI 에서 본 값. DB 는 .512882 로 반올림했지만 저장 전에 버리면 메모리와 DB 가 .512881 로 같다.
        "2026-11-05T05:18:07.512881776Z, 2026-11-05T05:18:07.512881Z",
        // 반올림이었다면 다음 초(05:00:01)로 넘어갔을 값.
        "2026-11-05T05:00:00.999999999Z, 2026-11-05T05:00:00.999999Z",
        "2026-11-05T05:00:00.000000999Z, 2026-11-05T05:00:00Z",
        // 이미 마이크로초 단위인 값과 정각은 그대로다.
        "2026-11-05T05:00:00.123456Z, 2026-11-05T05:00:00.123456Z",
        "2026-11-05T05:00:00Z, 2026-11-05T05:00:00Z",
        // 1970 년 이전도 같은 자리(마이크로초 아래)만 버린다.
        "1969-12-31T23:59:59.999999999Z, 1969-12-31T23:59:59.999999Z"
    })
    void dropsDigitsBelowTheStoredResolution(String input, String expected) {
        assertThat(StoredTime.normalize(Instant.parse(input))).isEqualTo(Instant.parse(expected));
    }

    @Test
    void normalizingTwiceChangesNothing() {
        Instant once = StoredTime.normalize(Instant.parse("2026-11-05T05:00:00.123456789Z"));

        assertThat(StoredTime.normalize(once)).isEqualTo(once);
    }

    @Test
    void keepsAMissingTimeMissing() {
        assertThat(StoredTime.normalize(null)).isNull();
    }
}
