package com.neuringo.neuringobe.auth.security;

import com.neuringo.neuringobe.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <token>} 을 DB 세션으로 검증해 {@link AuthenticatedUser} 를 SecurityContext 에
 * 넣는다(DEC-001 1.1절 B안).
 *
 * <p>빈(@Component)으로 등록하지 않는다. 빈으로 두면 Spring Boot 가 서블릿 필터로도 한 번 더 등록해 두 번 실행된다. {@code
 * SecurityConfig} 에서 직접 만들어 SecurityFilterChain 에만 넣는다.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final AuthenticationEntryPoint entryPoint;

    public BearerTokenFilter(AuthService authService, AuthenticationEntryPoint entryPoint) {
        this.authService = authService;
        this.entryPoint = entryPoint;
    }

    // 토큰을 쓰지 않는 요청은 토큰이 달려 있어도 검사하지 않는다. 틀린 토큰 때문에 가입·로그인이 막히지 않게 하기 위함이다.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return CorsUtils.isPreFlightRequest(request) || PublicEndpoints.MATCHER.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 헤더가 없으면 인증을 시도하지 않는다. 보호 경로면 뒤의 AuthorizationFilter 가 막고 EntryPoint 가
        // AUTHENTICATION_REQUIRED 로 응답한다.
        if (header == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token =
                header.startsWith(BEARER_PREFIX)
                        ? header.substring(BEARER_PREFIX.length()).trim()
                        : "";
        Optional<AuthenticatedUser> user =
                token.isEmpty() ? Optional.empty() : authService.authenticate(token);

        // 이 필터는 ExceptionTranslationFilter 보다 앞에 있어 throw 하면 500 이 된다. EntryPoint 를 직접 부르고 체인을 멈춘다.
        if (user.isEmpty()) {
            entryPoint.commence(
                    request, response, new InvalidTokenException("invalid bearer token"));
            return;
        }

        setAuthentication(user.get());
        filterChain.doFilter(request, response);
    }

    private void setAuthentication(AuthenticatedUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        java.util.List.of(
                                new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
        SecurityContextHolder.setContext(context);
    }
}
