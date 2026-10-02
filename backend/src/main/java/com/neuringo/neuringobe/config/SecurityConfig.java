package com.neuringo.neuringobe.config;

import com.neuringo.neuringobe.auth.security.BearerTokenFilter;
import com.neuringo.neuringobe.auth.security.PublicEndpoints;
import com.neuringo.neuringobe.auth.service.AuthService;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /** 로컬 개발 정책. 다른 프로필과 인증 규칙은 같고, 프론트 개발 서버(5173)용 CORS 와 쿠키 CSRF 만 더한다. */
    @Bean
    @Profile("local")
    public SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http,
            AuthService authService,
            ApiSecurityFailureHandler failures,
            HttpSessionSecurityContextRepository contextRepository)
            throws Exception {
        // CSRF 토큰은 쿠키에 보관한다(HttpOnly 유지). 프론트는 쿠키를 직접 읽지 않고
        // GET /api/v1/csrf 응답으로 토큰 값을 받아 헤더에 싣는다 — CsrfController 참고.
        http.csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))
                .cors(cors -> cors.configurationSource(localCorsConfigurationSource()));
        applyAuthentication(http, authService, failures, contextRepository);
        return http.build();
    }

    /** local 이 아닌 모든 환경(prod 포함, 테스트 기본 프로필)의 정책. 공개 경로 외에는 모두 인증을 요구한다. */
    @Bean
    @Profile("!local")
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http,
            AuthService authService,
            ApiSecurityFailureHandler failures,
            HttpSessionSecurityContextRepository contextRepository)
            throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()));
        applyAuthentication(http, authService, failures, contextRepository);
        return http.build();
    }

    @Bean
    public HttpSessionSecurityContextRepository childSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 강사는 Bearer 토큰, 아동은 서버 세션으로 인증한다.
    private void applyAuthentication(
            HttpSecurity http,
            AuthService authService,
            ApiSecurityFailureHandler failures,
            HttpSessionSecurityContextRepository contextRepository)
            throws Exception {
        http.sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(failures)
                                        .accessDeniedHandler(failures))
                .addFilterBefore(
                        new BearerTokenFilter(authService, failures),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(
                        auth ->
                                // 필터 단계 예외로 컨테이너가 다시 보낸 /error 요청은 Bearer 인증을 거치지 않는다
                                // (BearerTokenFilter 는 ERROR 디스패치에서 돌지 않는다). 막으면 강사 요청은 원래 상태와
                                // 상관없이 401 이 되므로 열고, 응답은 ApiErrorController 가 쓴다.
                                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(PublicEndpoints.MATCHER)
                                        .permitAll()
                                        .requestMatchers("/actuator/health", "/actuator/info")
                                        .hasAnyRole("INSTRUCTOR", "OPERATOR")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.DELETE,
                                                "/api/v1/child-access-sessions/current")
                                        .hasRole("CHILD")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/v1/children/*/activities",
                                                "/api/v1/activities/*/quiz-items",
                                                "/api/v1/activities/*/quiz-result",
                                                "/api/v1/activity-quiz-items/*/attempt")
                                        .hasAnyRole("CHILD", "INSTRUCTOR")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.PUT,
                                                "/api/v1/activity-quiz-items/*/attempt")
                                        .hasRole("CHILD")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.PATCH,
                                                "/api/v1/activity-quiz-items/*/attempt")
                                        .hasRole("SYSTEM")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/v1/activity-quiz-items/*/hints")
                                        .hasRole("CHILD")
                                        .requestMatchers("/api/v1/auth/**", "/api/v1/users/me")
                                        .hasAnyRole("INSTRUCTOR", "OPERATOR")
                                        .anyRequest()
                                        .hasRole("INSTRUCTOR"));
    }

    private CorsConfigurationSource localCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // CSRF 쿠키가 다른 Origin(5173)과 오갈 수 있어야 한다. 허용 Origin 은 위에서 명시적으로 제한한다.
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
