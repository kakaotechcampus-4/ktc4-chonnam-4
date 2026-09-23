package com.neuringo.neuringobe.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /**
     * 로컬 개발 전용 정책. 인증 없이 학급·아동 경로를 호출할 수 있게 열어 둔다. 인증·세션·소유권 정책은 G0 미결정이므로 여기서 정하지 않고, 결정되면 아래 기본
     * 정책을 대체한다.
     */
    @Bean
    @Profile("local")
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        // CSRF 토큰은 쿠키에 보관한다(HttpOnly 유지). 프론트는 쿠키를 직접 읽지 않고
        // GET /api/v1/csrf 응답으로 토큰 값을 받아 헤더에 싣는다 — CsrfController 참고.
        http.csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))
                .cors(cors -> cors.configurationSource(localCorsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** local 이 아닌 모든 환경(prod 포함, 테스트 기본 프로필)의 정책. 개발용 전체 허용이 새어 나가지 않도록 기본값을 차단으로 둔다. */
    @Bean
    @Profile("!local")
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
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
