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
 * @param retention 이 기간이 지난 기록은 지운다
 */
@ConfigurationProperties("neuringo.agent-runs")
public record AgentRunProperties(boolean enabled, List<String> operators, Duration retention) {
    public AgentRunProperties {
        operators =
                operators == null
                        ? List.of()
                        : operators.stream()
                                .map(email -> email.strip().toLowerCase(Locale.ROOT))
                                .filter(email -> !email.isEmpty())
                                .toList();
        if (retention == null) retention = Duration.ofDays(14);
        if (retention.isNegative() || retention.isZero())
            throw new IllegalArgumentException("neuringo.agent-runs.retention 은 0보다 커야 합니다.");
    }

    public boolean everyInstructor() {
        return operators.contains("*");
    }
}
