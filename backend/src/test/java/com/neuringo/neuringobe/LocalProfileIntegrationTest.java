package com.neuringo.neuringobe;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * local 프로필(개발용 보안 정책: 다른 프로필과 같은 토큰 인증 + 쿠키 CSRF + CORS 5173)로 도는 통합 테스트.
 *
 * <p>API 를 HTTP 로 불러 성공을 확인하는 테스트는 이 애너테이션을 쓴다. 프론트와 같은 쿠키 CSRF 흐름이라 {@link TestFixtures} 를 주입받아 쓸
 * 수 있다. 학급·아동 API 는 로그인이 필요하므로 픽스처가 테스트 강사를 가입·로그인시켜 토큰을 싣는다.
 *
 * <p>DB 는 Testcontainers 가 대신하므로 application-local.yml 의 DB 계정은 쓰이지 않는다. 개발자 PC 의 환경변수와 상관없이 뜨도록 더미
 * 값을 넣는다.
 *
 * <p>변경 요청에 {@code csrf()} 후처리기를 쓰지 않는다. 공유 컨텍스트의 CSRF 저장소를 세션 저장소로 바꿔 버려, 같은 컨텍스트를 쓰는 다른 테스트의 쿠키
 * CSRF 흐름까지 깨진다. 대신 {@link TestFixtures#postJson} 처럼 GET /api/v1/csrf 로 받은 쿠키와 헤더를 싣는다(프론트와 같은 흐름).
 *
 * <p>트랜잭션을 걸지 않는 이유와 컨텍스트 공유는 {@link IntegrationTest} 와 같다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {"DB_USERNAME=unused", "DB_PASSWORD=unused"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({TestcontainersConfiguration.class, TestFixtures.class})
public @interface LocalProfileIntegrationTest {}
