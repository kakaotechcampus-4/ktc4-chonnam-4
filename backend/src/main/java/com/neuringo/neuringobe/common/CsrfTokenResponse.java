package com.neuringo.neuringobe.common;

import org.springframework.security.web.csrf.CsrfToken;

/** 프론트가 변경 요청에 붙일 CSRF 토큰과 헤더 이름. 헤더 이름을 함께 내려 프론트가 문자열을 하드코딩하지 않게 한다. */
public record CsrfTokenResponse(String headerName, String token) {

    public static CsrfTokenResponse from(CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }
}
