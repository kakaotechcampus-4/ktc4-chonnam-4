package com.neuringo.neuringobe.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * local 프로필 보안 정책의 약속(SecurityConfig#localSecurityFilterChain, CsrfController). 인증 규칙은 다른 프로필과
 * 같고(AuthIntegrationTest), 여기서는 local 에만 더한 쿠키 CSRF 와 CORS 를 본다.
 *
 * <ul>
 *   <li>변경 요청은 로그인한 강사라도 CSRF 토큰 헤더가 없으면 403 이다. 브라우저가 쿠키를 자동으로 실어 보내도 헤더가 없으면 막는다.
 *   <li>GET /api/v1/csrf 응답 본문의 헤더 이름·토큰을 싣고 쿠키를 함께 보내면 통과한다(프론트 흐름).
 *   <li>CSRF 쿠키는 HttpOnly 다. 스크립트는 쿠키를 읽지 못하므로 토큰은 응답 본문으로만 받는다.
 *   <li>CORS 는 프론트 개발 서버(http://localhost:5173)만 허용한다. 다른 Origin 의 preflight 는 403 이다.
 * </ul>
 *
 * <p>csrf() 후처리기는 쓰지 않는다(LocalProfileIntegrationTest 참고).
 */
@LocalProfileIntegrationTest
class LocalSecurityPolicyTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @Autowired private MockMvcTester mvc;

    @Autowired private TestFixtures fixtures;

    @Test
    void rejectsPostWithoutCsrfHeaderEvenWhenCookieIsSent() {
        Cookie[] csrfCookies = mvc.get().uri("/api/v1/csrf").exchange().getResponse().getCookies();
        String name = "토큰 없는 요청 " + UUID.randomUUID();

        MvcTestResult withoutToken = createClassroom(name).exchange();
        MvcTestResult cookieOnly = createClassroom(name).cookie(csrfCookies).exchange();

        assertThat(withoutToken).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(cookieOnly).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(fixtures.get("/api/v1/classrooms"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.data[*].name")
                .asArray()
                .doesNotContain(name);
    }

    @Test
    void acceptsPostCarryingTokenFromCsrfEndpoint() {
        MvcTestResult csrf = mvc.get().uri("/api/v1/csrf").exchange();
        String json = TestFixtures.body(csrf);
        String headerName = JsonPath.read(json, "$.data.headerName");
        String token = JsonPath.read(json, "$.data.token");
        String name = "토큰 있는 요청 " + UUID.randomUUID();

        MvcTestResult created =
                createClassroom(name)
                        .header(headerName, token)
                        .cookie(csrf.getResponse().getCookies())
                        .exchange();

        assertThat(created)
                .hasStatus2xxSuccessful()
                .bodyJson()
                .extractingPath("$.data.name")
                .asString()
                .isEqualTo(name);
    }

    @Test
    void keepsCsrfCookieHttpOnly() {
        Cookie[] cookies = mvc.get().uri("/api/v1/csrf").exchange().getResponse().getCookies();

        assertThat(cookies)
                .isNotEmpty()
                .allSatisfy(
                        cookie -> assertThat(cookie.isHttpOnly()).as(cookie.getName()).isTrue());
    }

    @Test
    void allowsPreflightFromFrontendDevServer() {
        assertThat(preflightFrom(FRONTEND_ORIGIN))
                .hasStatusOk()
                .headers()
                .hasValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN)
                .hasValue(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @Test
    void rejectsPreflightFromUnknownOrigin() {
        assertThat(preflightFrom("https://evil.example"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    /** 로그인한 강사의 학급 생성 요청. CSRF 만 테스트마다 다르게 싣는다. */
    private MockMvcTester.MockMvcRequestBuilder createClassroom(String name) {
        return fixtures.authorized(
                mvc.post()
                        .uri("/api/v1/classrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"));
    }

    private MvcTestResult preflightFrom(String origin) {
        return mvc.options()
                .uri("/api/v1/classrooms")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token")
                .exchange();
    }
}
