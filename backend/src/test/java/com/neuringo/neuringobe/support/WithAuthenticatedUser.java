package com.neuringo.neuringobe.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * 테스트에서 Bearer 토큰 필터를 거치지 않고 {@link com.neuringo.neuringobe.auth.security.AuthenticatedUser} 주체를
 * SecurityContext 에 넣는다. Controller 의 {@code @AuthenticationPrincipal AuthenticatedUser} 가 값을 받는다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@WithSecurityContext(factory = WithAuthenticatedUserSecurityContextFactory.class)
public @interface WithAuthenticatedUser {

    /** 강사 ID(UUID 문자열). */
    String userId() default "00000000-0000-0000-0000-000000000001";

    String sessionId() default "00000000-0000-0000-0000-00000000000a";
}
