package com.neuringo.neuringobe.auth.security;

import java.util.UUID;

/**
 * 토큰 검증을 통과한 요청의 인증 주체. Bearer 토큰 필터가 {@code SecurityContext} 에 넣고, Controller 는
 * {@code @AuthenticationPrincipal} 로 받는다. 로그아웃이 현재 세션만 폐기할 수 있도록 세션 ID 도 함께 담는다.
 */
public record AuthenticatedUser(
        UUID userId, UUID sessionId, com.neuringo.neuringobe.user.domain.UserRole role) {
    public AuthenticatedUser(UUID userId, UUID sessionId) {
        this(userId, sessionId, com.neuringo.neuringobe.user.domain.UserRole.INSTRUCTOR);
    }
}
