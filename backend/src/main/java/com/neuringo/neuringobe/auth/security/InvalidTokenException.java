package com.neuringo.neuringobe.auth.security;

import org.springframework.security.core.AuthenticationException;

/**
 * {@code Authorization} 헤더는 있지만 토큰이 형식 오류·미존재·만료·폐기인 경우. {@link BearerTokenFilter} 가 EntryPoint 에
 * 직접 넘기며, EntryPoint 는 이 타입을 보고 {@code INVALID_TOKEN} 으로 응답한다.
 */
public class InvalidTokenException extends AuthenticationException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
