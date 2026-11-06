package com.neuringo.neuringobe.ai.config;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 호출 목적별 모델 경로. 엘리스 ML API 처럼 모델마다 주소가 다른 게이트웨이에서 리포트 초안과 평가를 다른 모델로 보낼 때 쓴다. base-url 과 model 이 둘
 * 다 있는 경로만 켜지고, 나머지 목적은 기본 제공자(AI_BASE_URL·AI_MODEL)로 간다. 키는 기본 제공자와 같은 spring.ai.openai.api-key 를
 * 쓴다.
 *
 * <p>키 이름은 AiOperation 을 소문자·하이픈으로 쓴다(REPORT_GENERATION 은 report-generation).
 */
@ConfigurationProperties(prefix = "neuringo.ai")
public record AiRouteProperties(Map<String, Route> routes) {

    public AiRouteProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
    }

    /** 켜진 경로만 목적별로 돌려준다. 모르는 목적 이름은 설정 실수라 기동을 멈춘다. */
    public Map<AiOperation, Route> active() {
        Map<AiOperation, Route> active = new EnumMap<>(AiOperation.class);
        routes.forEach(
                (name, route) -> {
                    var operation =
                            AiOperation.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
                    if (route != null && route.enabled()) {
                        active.put(operation, route);
                    }
                });
        return active;
    }

    /**
     * @param requestTimeout 호출 하나의 제한 시간. 리포트 평가 모델은 p95 45초라 기본 60초
     * @param maxOutputTokens 출력 상한. 엘리스 게이트웨이는 비스트리밍 출력 2,000토큰(추론 포함)까지만 받는다
     * @param reasoningEffort 추론 강도. 비우면 보내지 않는다
     */
    public record Route(
            String baseUrl,
            String model,
            Duration requestTimeout,
            Integer maxOutputTokens,
            String reasoningEffort) {
        public Route {
            baseUrl = blankToNull(baseUrl);
            model = blankToNull(model);
            reasoningEffort = blankToNull(reasoningEffort);
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
            if (maxOutputTokens != null && maxOutputTokens < 1) {
                throw new IllegalArgumentException("maxOutputTokens must be positive");
            }
        }

        public boolean enabled() {
            return baseUrl != null && model != null;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
