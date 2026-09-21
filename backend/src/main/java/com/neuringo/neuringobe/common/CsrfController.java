package com.neuringo.neuringobe.common;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프론트가 POST 전에 CSRF 토큰을 받아가는 경로.
 *
 * <p>토큰 생성·검증은 Spring Security 가 하고 여기서는 값만 전달한다. 응답 시 저장소(쿠키)에도 토큰이 기록되므로, 프론트는 이 응답의 토큰을 헤더에 싣고
 * 쿠키를 함께 보내면 된다.
 */
@RestController
public class CsrfController {

    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenResponse> csrf(CsrfToken csrfToken) {
        return ApiResponse.of(CsrfTokenResponse.from(csrfToken));
    }
}
