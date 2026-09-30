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
 * local 이 아닌 보안 정책(dev·prod 와 같은 토큰 인증, CORS 없음, 세션 CSRF)과 Testcontainers PostgreSQL 로 도는 통합 테스트.
 *
 * <p>API 를 HTTP 로 불러 성공을 확인하는 테스트는 {@link LocalProfileIntegrationTest} 를 쓴다. 이 애너테이션은 배포 프로필에서
 * "막히는지", DB 제약이 지켜지는지처럼 local 정책과 상관없는 것을 볼 때 쓴다.
 *
 * <p>트랜잭션을 걸지 않는다. {@code @Transactional} 테스트는 끝날 때 롤백하면서 flush 를 건너뛰어, 실제로는 터질 FK·CHECK·길이 위반이 통과해
 * 버린다. 테스트끼리는 데이터를 새로 만들어(무작위 UUID) 겹치지 않게 한다.
 *
 * <p>이 애너테이션을 쓰는 테스트 클래스는 Spring 컨텍스트와 DB 컨테이너를 함께 쓴다. 클래스에 설정을 덧붙이면 컨텍스트가 새로 뜨니 바꿀 것이 있으면 여기서 바꾼다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {}
