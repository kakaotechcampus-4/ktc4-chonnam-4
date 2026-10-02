package com.neuringo.neuringobe.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * local 이 아닌 환경(dev·prod)의 보안 정책(SecurityConfig#defaultSecurityFilterChain). 테스트는 "test" 프로필로 돌지만
 * {@code @Profile("!local")} 이라 dev·prod 와 같은 정책을 탄다. 인증·CSRF 규칙은 local 과 같고, 개발용 CORS 만 없다.
 *
 * <ul>
 *   <li>공개 경로(가입·로그인·CSRF 토큰) 밖의 조회는 토큰이 없으면 401 AUTHENTICATION_REQUIRED 다. actuator health·info 도
 *       지금은 로그인해야 본다(공개 여부는 팀 결정 전 — 열기로 하면 PublicEndpoints 와 이 목록을 같이 고친다).
 *   <li>막힌 변경은 아무것도 저장하지 않는다. CSRF 헤더·쿠키를 지어내 보내도 막힌다.
 *   <li>배포 프로필에서도 가입 → 로그인 → 토큰으로 조회·생성이 된다(쿠키 CSRF). 막기만 하고 열리지 않는 정책이면 배포가 쓸모없어진다.
 *   <li>local 이 아니면 CORS 를 열지 않는다. 프론트 개발 서버 Origin 의 preflight 도 막힌다.
 *   <li>막힌 응답에도 보안 헤더(nosniff·DENY·no-store)가 붙는다.
 * </ul>
 */
@IntegrationTest
class DefaultSecurityPolicyTest {

    private static final String SOME_CLASSROOM =
            "/api/v1/classrooms/00000000-0000-0000-0000-000000000001";

    @Autowired private MockMvcTester mvc;

    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/classrooms",
                SOME_CLASSROOM,
                SOME_CLASSROOM + "/children",
                "/api/v1/auth/session",
                "/actuator/health",
                "/actuator/info"
            })
    void requiresLoginForReads(String uri) {
        MvcTestResult result = mvc.get().uri(uri).exchange();

        assertThat(result.getResponse().getStatus()).as(uri).isEqualTo(401);
        assertThat(JsonPath.<String>read(TestFixtures.body(result), "$.error.code"))
                .isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void servesCsrfTokenWithoutLogin() {
        assertThat(mvc.get().uri("/api/v1/csrf")).hasStatusOk();
    }

    @Test
    void deniesWritesAndStoresNothing() {
        String name = "막혀야 하는 학급 " + UUID.randomUUID();

        MvcTestResult result =
                mvc.post()
                        .uri("/api/v1/classrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}")
                        .exchange();

        assertDenied(result);
        assertThat(countRows("SELECT COUNT(*) FROM classroom WHERE name = ?", name)).isZero();
    }

    @Test
    void deniesChildRegistrationAndStoresNothing() {
        String name = "막혀야 하는 아동 " + UUID.randomUUID();

        MvcTestResult result =
                mvc.post()
                        .uri(SOME_CLASSROOM + "/children")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + name + "\"}")
                        .exchange();

        assertDenied(result);
        assertThat(countRows("SELECT COUNT(*) FROM child WHERE display_name = ?", name)).isZero();
    }

    static Stream<Arguments> otherUnsafeRequests() {
        return Stream.of(
                Arguments.of(HttpMethod.PUT, SOME_CLASSROOM),
                Arguments.of(HttpMethod.PATCH, SOME_CLASSROOM),
                Arguments.of(HttpMethod.DELETE, SOME_CLASSROOM),
                Arguments.of(HttpMethod.DELETE, SOME_CLASSROOM + "/children"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/auth/session"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("otherUnsafeRequests")
    void deniesOtherUnsafeMethods(HttpMethod method, String uri) {
        assertDenied(
                mvc.method(method)
                        .uri(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .exchange());
    }

    @Test
    void deniesWritesEvenWithMadeUpCsrfHeaderAndCookie() {
        String name = "지어낸 CSRF 학급 " + UUID.randomUUID();

        MvcTestResult result =
                mvc.post()
                        .uri("/api/v1/classrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}")
                        .header("X-XSRF-TOKEN", "made-up-token")
                        .cookie(new Cookie("XSRF-TOKEN", "made-up-token"))
                        .exchange();

        assertDenied(result);
        assertThat(countRows("SELECT COUNT(*) FROM classroom WHERE name = ?", name)).isZero();
    }

    @Test
    void signsUpLogsInAndUsesTheTokenOutsideLocal() {
        TestFixtures.Credentials credentials = TestFixtures.newCredentials();
        String name = "배포 프로필 학급 " + UUID.randomUUID();

        MvcTestResult signup =
                post(
                        "/api/v1/users",
                        "{\"email\":\""
                                + credentials.email()
                                + "\",\"password\":\""
                                + credentials.password()
                                + "\",\"name\":\"테스트 강사\"}",
                        null);
        MvcTestResult login =
                post(
                        "/api/v1/auth/sessions",
                        "{\"email\":\""
                                + credentials.email()
                                + "\",\"password\":\""
                                + credentials.password()
                                + "\"}",
                        null);
        assertThat(signup).hasStatusOk();
        assertThat(login).hasStatusOk();
        String token = JsonPath.read(TestFixtures.body(login), "$.data.accessToken");

        MvcTestResult created = post("/api/v1/classrooms", "{\"name\":\"" + name + "\"}", token);
        MvcTestResult list =
                TestFixtures.bearer(mvc.get().uri("/api/v1/classrooms"), token).exchange();

        assertThat(created).hasStatusOk();
        assertThat(list).hasStatusOk();
        assertThat(JsonPath.<List<String>>read(TestFixtures.body(list), "$.data[*].name"))
                .containsExactly(name);
    }

    @Test
    void doesNotOpenCorsOutsideLocal() {
        MvcTestResult preflight =
                mvc.options()
                        .uri("/api/v1/classrooms")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .exchange();

        assertDenied(preflight);
        assertThat(preflight).doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void sendsSecurityHeadersOnDeniedResponses() {
        MvcTestResult result = mvc.get().uri("/api/v1/classrooms").exchange();

        assertDenied(result);
        LocalSecurityMatrixTest.assertSecurityHeaders(result);
    }

    /** 배포 프로필도 CSRF 쿠키와 응답 본문의 헤더 토큰을 함께 보낸다. */
    private MvcTestResult post(String uri, String json, String token) {
        MvcTestResult csrfResult = mvc.get().uri("/api/v1/csrf").exchange();
        String csrf = TestFixtures.body(csrfResult);
        MockMvcTester.MockMvcRequestBuilder request =
                mvc.post()
                        .uri(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .cookie(csrfResult.getResponse().getCookies())
                        .header(
                                JsonPath.read(csrf, "$.data.headerName"),
                                JsonPath.<String>read(csrf, "$.data.token"));
        return (token == null ? request : TestFixtures.bearer(request, token)).exchange();
    }

    private int countRows(String sql, String name) {
        return jdbcTemplate.queryForObject(sql, Integer.class, name);
    }

    private static void assertDenied(MvcTestResult result) {
        assertThat(result.getResponse().getStatus())
                .as("%s %s", result.getRequest().getMethod(), result.getRequest().getRequestURI())
                .isIn(401, 403);
    }
}
