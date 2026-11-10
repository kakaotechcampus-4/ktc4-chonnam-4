package com.neuringo.neuringobe.ops.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 에이전트 실행 기록 설정.
 *
 * @param enabled 켜면 실행 기록을 저장하고 조회 API(/api/v1/ops/agent-runs)를 연다
 * @param operators 대시보드를 볼 수 있는 강사 계정 이메일. 비어 있으면 아무도 볼 수 없고, {@code *} 하나면 로그인한 강사 모두가 본다
 * @param retention 이 기간이 지난 기록은 지운다. 0 이면 지우지 않고 계속 둔다(기본값, 시연·외부 평가에서 쌓인 기록을 남기려고)
 * @param publicRead 켜면 로그인 없이 누구나 조회한다(시연·외부 평가용). 저장 값이 고정 코드·횟수·시간·토큰 수뿐이라 열 수 있다. 끄면 operators
 *     규칙으로 돌아간다
 */
@ConfigurationProperties("neuringo.agent-runs")
public record AgentRunProperties(
        boolean enabled, List<String> operators, Duration retention, boolean publicRead) {
    public AgentRunProperties {
        operators =
                operators == null
                        ? List.of()
                        : operators.stream()
                                .map(email -> email.strip().toLowerCase(Locale.ROOT))
                                .filter(email -> !email.isEmpty())
                                .toList();
        if (retention == null) retention = Duration.ZERO;
        if (retention.isNegative())
            throw new IllegalArgumentException("neuringo.agent-runs.retention 은 0 이상이어야 합니다.");
    }

    /** 보관 기간이 0 이면 기록을 지우지 않는다. */
    public boolean keepForever() {
        return retention.isZero();
    }

    public boolean everyInstructor() {
        return operators.contains("*");
    }
}
