package com.neuringo.neuringobe.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * local 보안 정책을 요청 종류별로 넓게 본다. {@link LocalSecurityPolicyTest} 가 기본 흐름을, 이 클래스가 행렬(메서드 × 경로 × 토큰 ×
 * Origin)을 맡는다. 요청은 모두 로그인한 강사의 토큰을 싣는다. 인증을 통과해도 CSRF·CORS 가 막는지 본다.
 *
 * <ul>
 *   <li>CSRF: 토큰이 없거나, 틀리거나, 쿠키 값을 그대로 넣었거나, 다른 세션 쿠키와 짝지었으면 막는다. 아직 없는 API 의 PUT·PATCH·DELETE 와
 *       로그아웃(DELETE /api/v1/auth/session)도 토큰부터 본다. 막힌 요청은 아무것도 저장하지 않는다.
 *   <li>CORS: 프론트 개발 서버(http://localhost:5173)가 아니면 https·127.0.0.1·다른 포트·"null" Origin 도 preflight
 *       부터 막는다. 다른 Origin 의 변경 요청은 CSRF 토큰이 맞아도 막는다. 허용된 Origin 에는 프론트가 싣는 헤더(Content-Type,
 *       X-XSRF-TOKEN, Authorization)를 허락한다.
 *   <li>보안 헤더: 아동 이름이 담기는 API 응답은 브라우저·프록시에 캐시하지 않고(no-store), MIME 추측과 iframe 삽입을 막는다.
 *   <li>actuator 는 health·info 만 노출한다. 환경변수·빈·힙 덤프 같은 내부 정보는 로그인해도 404 다. health·info 도 지금은 로그인해야
 *       본다(공개 여부는 팀 결정 전 — 열기로 하면 PublicEndpoints 와 이 테스트를 같이 고친다).
 * </ul>
 */
@LocalProfileIntegrationTest
class LocalSecurityMatrixTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @Autowired private MockMvcTester mvc;

    @Autowired private TestFixtures fixtures;

    static Stream<Arguments> unsafeRequests() {
        String classroom = "/api/v1/classrooms/" + UUID.randomUUID();
        String children = classroom + "/children";
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/classrooms"),
                Arguments.of(HttpMethod.POST, children),
                Arguments.of(HttpMethod.PUT, classroom),
                Arguments.of(HttpMethod.PATCH, classroom),
                Arguments.of(HttpMethod.DELETE, classroom),
                Arguments.of(HttpMethod.PUT, children),
                Arguments.of(HttpMethod.PATCH, children),
                Arguments.of(HttpMethod.DELETE, children),
                Arguments.of(HttpMethod.DELETE, "/api/v1/auth/session"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unsafeRequests")
    void rejectsEveryUnsafeMethodWithoutCsrfToken(HttpMethod method, String uri) {
        MvcTestResult result =
                fixtures.authorized(
                                mvc.method(method)
                                        .uri(uri)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"토큰 없는 요청\"}"))
                        .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
    }

    /** 이름 하나를 받아 CSRF 가 잘못된 요청을 만든다. 쿠키·토큰은 실제로 GET /api/v1/csrf 로 받은 값을 섞어 쓴다. */
    static Stream<Arguments> forgedCsrf() {
        return Stream.of(
                forged(
                        "틀린 토큰",
                        (t, request) -> {
                            TestFixtures.CsrfCredentials csrf = t.fixtures.fetchCsrf();
                            return request.header(csrf.headerName(), "wrong-token")
                                    .cookie(csrf.cookies());
                        }),
                forged(
                        "쿠키 값을 헤더에 그대로",
                        (t, request) -> {
                            TestFixtures.CsrfCredentials csrf = t.fixtures.fetchCsrf();
                            return request.header(csrf.headerName(), csrf.cookies()[0].getValue())
                                    .cookie(csrf.cookies());
                        }),
                forged(
                        "쿠키 없이 토큰만",
                        (t, request) -> {
                            TestFixtures.CsrfCredentials csrf = t.fixtures.fetchCsrf();
                            return request.header(csrf.headerName(), csrf.token());
                        }),
                forged(
                        "다른 세션의 쿠키",
                        (t, request) -> {
                            TestFixtures.CsrfCredentials mine = t.fixtures.fetchCsrf();
                            Cookie[] others = t.fixtures.fetchCsrf().cookies();
                            return request.header(mine.headerName(), mine.token()).cookie(others);
                        }));
    }

    @ParameterizedTest(name = "학급 생성 — {0}")
    @MethodSource("forgedCsrf")
    void rejectsClassroomCreationWithForgedCsrf(
            String label,
            BiFunction<
                            LocalSecurityMatrixTest,
                            MockMvcTester.MockMvcRequestBuilder,
                            MockMvcTester.MockMvcRequestBuilder>
                    forge) {
        String name = "위조 CSRF 학급 " + UUID.randomUUID();

        MvcTestResult result =
                forge.apply(
                                this,
                                fixtures.authorized(
                                        json("/api/v1/classrooms", "{\"name\":\"" + name + "\"}")))
                        .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(JsonPath.<List<String>>read(listClassrooms(), "$.data[*].name"))
                .doesNotContain(name);
    }

    @ParameterizedTest(name = "아동 등록 — {0}")
    @MethodSource("forgedCsrf")
    void rejectsChildRegistrationWithForgedCsrf(
            String label,
            BiFunction<
                            LocalSecurityMatrixTest,
                            MockMvcTester.MockMvcRequestBuilder,
                            MockMvcTester.MockMvcRequestBuilder>
                    forge) {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        String uri = "/api/v1/classrooms/" + classId + "/children";

        MvcTestResult result =
                forge.apply(
                                this,
                                fixtures.authorized(
                                        json(
                                                uri,
                                                "{\"displayName\":\""
                                                        + TestFixtures.NAMESAKE
                                                        + "\"}")))
                        .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(JsonPath.<List<String>>read(TestFixtures.body(fixtures.get(uri)), "$.data"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:5174",
                "null"
            })
    void rejectsPreflightFromOtherOrigins(String origin) {
        assertThat(preflightFrom(origin))
                .hasStatus(HttpStatus.FORBIDDEN)
                .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void allowsTheHeadersTheFrontendSends() {
        MvcTestResult result = preflightFrom(FRONTEND_ORIGIN);

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .containsIgnoringCase("content-type")
                .containsIgnoringCase("x-xsrf-token")
                .containsIgnoringCase("authorization");
    }

    @Test
    void marksCrossOriginReadsForTheFrontendOnly() {
        MvcTestResult frontend =
                fixtures.authorized(
                                mvc.get()
                                        .uri("/api/v1/classrooms")
                                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                        .exchange();
        MvcTestResult other =
                fixtures.authorized(
                                mvc.get()
                                        .uri("/api/v1/classrooms")
                                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                        .exchange();

        assertThat(frontend)
                .hasStatusOk()
                .headers()
                .hasValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN)
                .hasValue(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        assertThat(other)
                .hasStatus(HttpStatus.FORBIDDEN)
                .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void rejectsWritesFromOtherOriginsEvenWithValidCsrf() {
        String name = "다른 Origin 학급 " + UUID.randomUUID();
        TestFixtures.CsrfCredentials csrf = fixtures.fetchCsrf();

        MvcTestResult result =
                fixtures.authorized(
                                json("/api/v1/classrooms", "{\"name\":\"" + name + "\"}")
                                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                                        .header(csrf.headerName(), csrf.token())
                                        .cookie(csrf.cookies()))
                        .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(JsonPath.<List<String>>read(listClassrooms(), "$.data[*].name"))
                .doesNotContain(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/classrooms", "/api/v1/csrf"})
    void sendsSecurityHeaders(String uri) {
        MvcTestResult result = fixtures.get(uri);

        assertThat(result).hasStatusOk();
        assertSecurityHeaders(result);
    }

    @Test
    void sendsSecurityHeadersOnChildList() {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        fixtures.createChild(classId, TestFixtures.NAMESAKE);

        MvcTestResult result = fixtures.get("/api/v1/classrooms/{classId}/children", classId);

        assertThat(result).hasStatusOk();
        assertSecurityHeaders(result);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/env",
                "/actuator/beans",
                "/actuator/configprops",
                "/actuator/heapdump",
                "/actuator/threaddump",
                "/actuator/loggers",
                "/actuator/mappings",
                "/actuator/metrics"
            })
    void hidesInternalActuatorEndpointsEvenAfterLogin(String uri) {
        assertThat(fixtures.get(uri)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/info"})
    void requiresLoginForHealthAndInfoForNow(String uri) {
        assertThat(mvc.get().uri(uri)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(fixtures.get(uri)).hasStatusOk();
    }

    static void assertSecurityHeaders(MvcTestResult result) {
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private MockMvcTester.MockMvcRequestBuilder json(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String listClassrooms() {
        return TestFixtures.body(fixtures.get("/api/v1/classrooms"));
    }

    private MvcTestResult preflightFrom(String origin) {
        return mvc.options()
                .uri("/api/v1/classrooms")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(
                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                        "content-type,x-xsrf-token,authorization")
                .exchange();
    }

    private static Arguments forged(
            String label,
            BiFunction<
                            LocalSecurityMatrixTest,
                            MockMvcTester.MockMvcRequestBuilder,
                            MockMvcTester.MockMvcRequestBuilder>
                    forge) {
        return Arguments.of(label, forge);
    }
}
