package com.neuringo.neuringobe.common.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * DB 에 저장할 시각을 PostgreSQL {@code TIMESTAMPTZ} 의 저장 해상도(1마이크로초)에 맞춘다.
 *
 * <p>{@link Instant} 는 나노초(소수점 아래 9자리)까지 담지만 {@code TIMESTAMPTZ} 는 마이크로초(6자리)까지만 담고, 넘치는 자리는 저장할 때
 * 반올림된다. 엔티티에 나노초 값을 그대로 두면 저장 직후 응답은 메모리 값({@code .512881776})을, 같은 요청 재전송·조회 응답은 DB 값({@code
 * .512882})을 보여 줘 두 응답이 달라진다(#104).
 *
 * <p>그래서 저장할 시각은 엔티티에 넣기 전에 이 함수로 자른다. 메모리 값과 DB 값이 처음부터 같아진다. 반올림이 아니라 버림이라 {@code .999999999} 같은
 * 값도 다음 초로 넘어가지 않고, 이미 마이크로초 단위인 값에 다시 적용해도 그대로다.
 *
 * <p>적용 책임은 엔티티에 둔다. 영속 {@link Instant} 필드에 값을 넣는 생성자·팩터리·상태 변경 메서드가 이 함수를 거친다. 호출하는 쪽이 {@code
 * Instant.now()}·주입된 {@code Clock}·요청에서 받은 값 중 무엇을 넘겨도 같은 원칙이 지켜진다. 역할극 제한 시간처럼 저장하지 않는 경과 시간 측정에는
 * 쓰지 않는다.
 */
public final class StoredTime {

    /** PostgreSQL {@code TIMESTAMPTZ} 의 저장 해상도. */
    public static final ChronoUnit RESOLUTION = ChronoUnit.MICROS;

    private StoredTime() {}

    /** 저장 해상도 아래 자리를 버린다. {@code null} 은 그대로 돌려준다(아직 일어나지 않은 시각). */
    public static Instant normalize(Instant instant) {
        return instant == null ? null : instant.truncatedTo(RESOLUTION);
    }
}
