package com.neuringo.neuringobe.config;

import com.neuringo.neuringobe.auth.security.ApiAuthenticationEntryPoint;
import com.neuringo.neuringobe.auth.security.BearerTokenFilter;
import com.neuringo.neuringobe.auth.security.PublicEndpoints;
import com.neuringo.neuringobe.auth.service.AuthService;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

    /** 로컬 개발 정책. 다른 프로필과 인증 규칙은 같고, 프론트 개발 서버(5173)용 CORS 와 쿠키 CSRF 만 더한다. */
    @Bean
    @Profile("local")
    public SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http, AuthService authService, ObjectMapper objectMapper)
            throws Exception {
        // CSRF 토큰은 쿠키에 보관한다(HttpOnly 유지). 프론트는 쿠키를 직접 읽지 않고
        // GET /api/v1/csrf 응답으로 토큰 값을 받아 헤더에 싣는다 — CsrfController 참고.
        http.csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))
                .cors(cors -> cors.configurationSource(localCorsConfigurationSource()));
        applyTokenAuthentication(http, authService, objectMapper);
        return http.build();
    }

    /** local 이 아닌 모든 환경(prod 포함, 테스트 기본 프로필)의 정책. 공개 경로 외에는 모두 인증을 요구한다. */
    @Bean
    @Profile("!local")
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http, AuthService authService, ObjectMapper objectMapper)
            throws Exception {
        applyTokenAuthentication(http, authService, objectMapper);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 두 프로필이 같이 쓰는 인증 규칙(DEC-001 1절). 인증 상태는 매 요청 Bearer 토큰으로 정하므로 서버 세션(JSESSIONID)을 만들지 않는다.
    private void applyTokenAuthentication(
            HttpSecurity http, AuthService authService, ObjectMapper objectMapper)
            throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(objectMapper);

        http.sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .addFilterBefore(
                        new BearerTokenFilter(authService, entryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PublicEndpoints.MATCHER)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated());
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
