package com.neuringo.neuringobe.auth.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 인증 없이 호출하는 요청(메서드+경로 정확히 일치). {@code SecurityConfig} 의 {@code permitAll} 과 {@link
 * BearerTokenFilter} 의 검증 제외가 둘 다 이 값을 읽는다(DEC-001 1.1절).
 *
 * <p>공개 경로를 추가할 때는 여기만 고치고, "틀린 토큰을 달아도 통과한다" 테스트를 하나 붙인다. 한쪽에만 넣으면 만료 토큰이 남은 사용자만 401 을
 * 받거나(permitAll 에만), 모두가 401 을 받는다(제외 목록에만).
 */
public final class PublicEndpoints {

    private static final PathPatternRequestMatcher.Builder PATH =
            PathPatternRequestMatcher.withDefaults();

    public static final RequestMatcher MATCHER =
            new OrRequestMatcher(
                    PATH.matcher(HttpMethod.POST, "/api/v1/users"),
                    PATH.matcher(HttpMethod.POST, "/api/v1/auth/sessions"),
                    // 가입·로그인 POST 전에 CSRF 토큰을 받아야 하므로 로그인 전에도 열려 있어야 한다(CSRF 유지, DEC-001 1.2절).
                    PATH.matcher(HttpMethod.GET, "/api/v1/csrf"));

    private PublicEndpoints() {}
}
