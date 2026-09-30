package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 강사 가입·로그인(VS-001 인수 조건)을 HTTP 로 확인한다. 기능 내부(필터·서비스)가 아니라 API 바깥에서 보이는 약속만 본다.
 *
 * <ul>
 *   <li>"활성 강사가 로그인하고 본인 정보를 조회할 수 있다": 가입 → 로그인 → GET /api/v1/auth/session 이 같은 강사를 돌려준다.
 *   <li>"인증이 없거나 만료되면 보호된 기능을 사용할 수 없다": 헤더가 없으면 401 AUTHENTICATION_REQUIRED, 틀린·만료·로그아웃한 토큰은 401
 *       INVALID_TOKEN 이다. 막힌 변경 요청은 저장하지 않는다. 로그아웃은 그 세션만 끝내고 같은 강사의 다른 세션은 그대로 둔다.
 *   <li>"비밀번호는 Spring Security 의 적응형 단방향 인코더로 저장된다": DB 에는 BCrypt 해시만 있고 원문은 없다. 로그인 토큰도 원문이 아니라
 *       해시로 저장된다.
 *   <li>"비밀번호는 8자 이상이며 추가 조합 규칙은 적용하지 않는다": 7자는 422, 8자는 숫자만·같은 글자만이어도 가입된다. BCrypt 한계(72바이트)를 넘으면
 *       500 이 아니라 422 다.
 *   <li>"이메일은 공백과 대소문자를 정규화해 서비스 전체에서 중복 등록되지 않는다": 대소문자·앞뒤 공백만 다른 이메일은 409, 로그인은 어느 쪽으로도 된다.
 *   <li>로그인 실패는 틀린 비밀번호·없는 이메일·제어 문자가 든 이메일 모두 같은 401 INVALID_CREDENTIALS 다(계정 존재 여부를 드러내지 않는다).
 *   <li>"로그인 실패 횟수 제한·잠금은 적용하지 않으며": 여러 번 틀린 뒤에도 맞는 비밀번호로 로그인된다.
 *   <li>공개 경로(가입·로그인·CSRF 토큰)는 틀린 토큰이 달려 있어도 열린다(PublicEndpoints 의 규칙).
 * </ul>
 *
 * <p>약관 동의 기록(버전·시각)과 정지·탈퇴 계정의 로그인 차단은 S3-BAE-01 범위라 여기서 보지 않는다.
 */
@LocalProfileIntegrationTest
class AuthIntegrationTest {

    private static final String BCRYPT_HASH = "^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$";

    @Autowired private MockMvcTester mvc;

    @Autowired private TestFixtures fixtures;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void signedUpInstructorLogsInAndReadsOwnProfile() {
        TestFixtures.Credentials credentials = TestFixtures.newCredentials();

        MvcTestResult signup =
                fixtures.signUpRequest(
                        credentials.email(), credentials.password(), TestFixtures.INSTRUCTOR_NAME);
        String token = fixtures.login(credentials.email(), credentials.password());
        MvcTestResult me =
                TestFixtures.bearer(mvc.get().uri("/api/v1/auth/session"), token).exchange();

        assertThat(signup).hasStatusOk();
        assertThat(me).hasStatusOk();
        Map<String, Object> signedUp = JsonPath.read(TestFixtures.body(signup), "$.data");
        Map<String, Object> current = JsonPath.read(TestFixtures.body(me), "$.data.user");
        assertThat(current).isEqualTo(signedUp);
        assertThat(current)
                .containsEntry("email", credentials.email())
                .containsEntry("name", TestFixtures.INSTRUCTOR_NAME)
                .containsEntry("role", "INSTRUCTOR")
                .containsEntry("status", "ACTIVE");
    }

    /** 로그인해야 쓸 수 있는 요청. 변경 요청은 CSRF 토큰을 실어서 CSRF 가 아니라 인증에서 막히는지 본다. */
    static Stream<Arguments> protectedRequests() {
        String someClassroom = "/api/v1/classrooms/" + UUID.randomUUID();
        return Stream.of(
                request("GET /api/v1/classrooms", (t, token) -> t.get("/api/v1/classrooms", token)),
                request("GET 학급 상세", (t, token) -> t.get(someClassroom, token)),
                request("GET 아동 목록", (t, token) -> t.get(someClassroom + "/children", token)),
                request(
                        "GET /api/v1/auth/session",
                        (t, token) -> t.get("/api/v1/auth/session", token)),
                request(
                        "POST /api/v1/classrooms",
                        (t, token) ->
                                t.send(
                                        t.mvc.post().uri("/api/v1/classrooms"),
                                        "{\"name\":\"인증 없는 학급\"}",
                                        token)),
                request(
                        "POST 아동 등록",
                        (t, token) ->
                                t.send(
                                        t.mvc.post().uri(someClassroom + "/children"),
                                        "{\"displayName\":\"인증 없는 아동\"}",
                                        token)),
                request(
                        "DELETE /api/v1/auth/session",
                        (t, token) ->
                                t.send(t.mvc.delete().uri("/api/v1/auth/session"), null, token)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedRequests")
    void rejectsProtectedRequestsWithoutToken(
            String label, BiFunction<AuthIntegrationTest, String, MvcTestResult> request) {
        assertUnauthorized(request.apply(this, null), "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedRequests")
    void rejectsProtectedRequestsWithMadeUpToken(
            String label, BiFunction<AuthIntegrationTest, String, MvcTestResult> request) {
        assertUnauthorized(request.apply(this, "not-a-real-token"), "INVALID_TOKEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Basic dXNlcjpwYXNz", "Token abc"})
    void rejectsMalformedAuthorizationHeaders(String header) {
        MvcTestResult result =
                mvc.get()
                        .uri("/api/v1/classrooms")
                        .header(HttpHeaders.AUTHORIZATION, header)
                        .exchange();

        assertUnauthorized(result, "INVALID_TOKEN");
    }

    @Test
    void rejectsExpiredSession() {
        String rawToken = "expired-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO auth_session (session_id, user_id, token_hash, expires_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                fixtures.instructor().userId(),
                sha256Hex(rawToken),
                Timestamp.from(now.minus(Duration.ofMinutes(1))),
                Timestamp.from(now.minus(Duration.ofHours(13))));

        assertUnauthorized(get("/api/v1/classrooms", rawToken), "INVALID_TOKEN");
    }

    @Test
    void logoutEndsOnlyThatSession() {
        TestFixtures.Instructor instructor = fixtures.signUpInstructor();
        String otherDevice = fixtures.login(instructor.email(), instructor.password());

        MvcTestResult logout =
                send(mvc.delete().uri("/api/v1/auth/session"), null, instructor.accessToken());

        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertUnauthorized(get("/api/v1/auth/session", instructor.accessToken()), "INVALID_TOKEN");
        assertThat(get("/api/v1/auth/session", otherDevice)).hasStatusOk();
    }

    @Test
    void rejectedWritesStoreNothing() {
        String name = "인증 없는 학급 " + UUID.randomUUID();

        MvcTestResult result =
                send(mvc.post().uri("/api/v1/classrooms"), "{\"name\":\"" + name + "\"}", null);

        assertUnauthorized(result, "AUTHENTICATION_REQUIRED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM classroom WHERE name = ?",
                                Integer.class,
                                name))
                .isZero();
    }

    @Test
    void publicEndpointsStayOpenWithAStaleToken() {
        String stale = "Bearer stale-" + UUID.randomUUID();
        TestFixtures.Credentials credentials = TestFixtures.newCredentials();
        TestFixtures.CsrfCredentials csrf = fixtures.fetchCsrf();

        MvcTestResult csrfEndpoint =
                mvc.get().uri("/api/v1/csrf").header(HttpHeaders.AUTHORIZATION, stale).exchange();
        MvcTestResult signup =
                mvc.post()
                        .uri("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\""
                                        + credentials.email()
                                        + "\",\"password\":\""
                                        + credentials.password()
                                        + "\",\"name\":\"테스트 강사\"}")
                        .header(csrf.headerName(), csrf.token())
                        .cookie(csrf.cookies())
                        .header(HttpHeaders.AUTHORIZATION, stale)
                        .exchange();
        TestFixtures.CsrfCredentials loginCsrf = fixtures.fetchCsrf();
        MvcTestResult login =
                mvc.post()
                        .uri("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\""
                                        + credentials.email()
                                        + "\",\"password\":\""
                                        + credentials.password()
                                        + "\"}")
                        .header(loginCsrf.headerName(), loginCsrf.token())
                        .cookie(loginCsrf.cookies())
                        .header(HttpHeaders.AUTHORIZATION, stale)
                        .exchange();

        assertThat(csrfEndpoint).hasStatusOk();
        assertThat(signup).hasStatusOk();
        assertThat(login).hasStatusOk();
    }

    @Test
    void storesOnlyAnAdaptiveHashOfThePassword() {
        TestFixtures.Instructor instructor = fixtures.signUpInstructor();

        String hash =
                jdbcTemplate.queryForObject(
                        "SELECT password_hash FROM user_account WHERE user_id = ?",
                        String.class,
                        instructor.userId());

        assertThat(hash).matches(BCRYPT_HASH).doesNotContain(instructor.password());
    }

    @Test
    void storesOnlyAHashOfTheLoginToken() {
        TestFixtures.Instructor instructor = fixtures.signUpInstructor();

        List<String> hashes =
                jdbcTemplate.queryForList(
                        "SELECT token_hash FROM auth_session WHERE user_id = ?",
                        String.class,
                        instructor.userId());

        assertThat(hashes).containsExactly(sha256Hex(instructor.accessToken()));
        assertThat(hashes).noneMatch(hash -> hash.contains(instructor.accessToken()));
    }

    @Test
    void rejectsPasswordsShorterThanEightCharacters() {
        MvcTestResult result =
                fixtures.signUpRequest(
                        TestFixtures.newCredentials().email(),
                        "abcdefg",
                        TestFixtures.INSTRUCTOR_NAME);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[*].field")
                .asArray()
                .containsOnly("password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaa", "12345678", "!!!!!!!!", "비밀번호여덟글자"})
    void acceptsAnyEightCharacterPasswordWithoutCompositionRules(String password) {
        TestFixtures.Credentials credentials = TestFixtures.newCredentials();

        assertThat(
                        fixtures.signUpRequest(
                                credentials.email(), password, TestFixtures.INSTRUCTOR_NAME))
                .hasStatusOk();
        assertThat(fixtures.loginRequest(credentials.email(), password)).hasStatusOk();
    }

    @Test
    void rejectsPasswordsBeyondTheHashLimitAsValidationError() {
        // 한글은 UTF-8 로 3바이트라 25자면 75바이트다(BCrypt 는 72바이트까지 본다).
        MvcTestResult result =
                fixtures.signUpRequest(
                        TestFixtures.newCredentials().email(),
                        "가".repeat(25),
                        TestFixtures.INSTRUCTOR_NAME);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void normalizesEmailCaseAndSpacesForSignupAndLogin() {
        TestFixtures.Credentials credentials = TestFixtures.newCredentials();
        String shouted = "  " + credentials.email().toUpperCase() + " ";

        MvcTestResult first =
                fixtures.signUpRequest(
                        shouted, credentials.password(), TestFixtures.INSTRUCTOR_NAME);
        MvcTestResult second =
                fixtures.signUpRequest(
                        credentials.email(), credentials.password(), TestFixtures.INSTRUCTOR_NAME);

        assertThat(first)
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.data.email")
                .isEqualTo(credentials.email());
        assertThat(second)
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("DUPLICATE_RESOURCE");
        assertThat(fixtures.loginRequest(credentials.email(), credentials.password()))
                .hasStatusOk();
        assertThat(fixtures.loginRequest(shouted, credentials.password())).hasStatusOk();
    }

    @Test
    void answersEveryLoginFailureTheSameWay() {
        TestFixtures.Instructor instructor = fixtures.signUpInstructor();

        List<MvcTestResult> failures =
                List.of(
                        fixtures.loginRequest(instructor.email(), "wrong-password"),
                        fixtures.loginRequest(
                                TestFixtures.newCredentials().email(), instructor.password()),
                        fixtures.loginRequest("a\u0000b@example.com", instructor.password()));

        for (MvcTestResult failure : failures) {
            assertThat(failure)
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .extractingPath("$.error.code")
                    .isEqualTo("INVALID_CREDENTIALS");
        }
        assertThat(failures.stream().map(f -> JsonPath.<String>read(body(f), "$.error.message")))
                .containsOnly("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    void doesNotLockTheAccountAfterRepeatedFailures() {
        TestFixtures.Instructor instructor = fixtures.signUpInstructor();

        for (int i = 0; i < 5; i++) {
            assertThat(fixtures.loginRequest(instructor.email(), "wrong-password-" + i))
                    .hasStatus(HttpStatus.UNAUTHORIZED);
        }

        assertThat(fixtures.loginRequest(instructor.email(), instructor.password())).hasStatusOk();
    }

    private MvcTestResult get(String uri, String token) {
        MockMvcTester.MockMvcRequestBuilder request = mvc.get().uri(uri);
        return (token == null ? request : TestFixtures.bearer(request, token)).exchange();
    }

    /** CSRF 토큰을 실어 변경 요청을 보낸다. token 이 null 이면 Authorization 을 싣지 않는다. */
    private MvcTestResult send(
            MockMvcTester.MockMvcRequestBuilder request, String json, String token) {
        TestFixtures.CsrfCredentials csrf = fixtures.fetchCsrf();
        request.header(csrf.headerName(), csrf.token()).cookie(csrf.cookies());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return (token == null ? request : TestFixtures.bearer(request, token)).exchange();
    }

    private static void assertUnauthorized(MvcTestResult result, String code) {
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.error.status").isEqualTo(401);
    }

    private static String body(MvcTestResult result) {
        return TestFixtures.body(result);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Arguments request(
            String label, BiFunction<AuthIntegrationTest, String, MvcTestResult> request) {
        return Arguments.of(label, request);
    }
}
