package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 백엔드 공통 픽스처. 프론트 MSW 픽스처(frontend/src/test/msw/handlers.ts)와 같은 이름을 쓴다.
 *
 * <ul>
 *   <li>학급 A1 "햇살반": 아동 A1-1·A1-2 는 동명이인 "김하늘"
 *   <li>학급 B1 "바람반": 아동 B1-1 "이바다"
 * </ul>
 *
 * <p>데이터는 API 를 HTTP 로 불러 만든다. Service 계층 리팩터링으로 Repository·Entity 가 바뀌어도 API 응답이 같으면 그대로 쓸 수 있다.
 * 부를 때마다 새로 만들기(무작위 UUID) 때문에 테스트끼리 겹치지 않는다. {@link LocalProfileIntegrationTest} 에서만 쓸 수 있다.
 *
 * <p>학급·아동 API 는 로그인한 강사만 부를 수 있다(VS-001). 픽스처는 처음 쓸 때 테스트 강사 한 명을 가입시키고 로그인해 두고, 학급·아동 요청에 그 강사의
 * 토큰을 싣는다. 이메일은 예약 도메인(example.com)에 무작위 이름을 붙이고, 비밀번호도 부를 때마다 새로 만든다. 레포에 고정 계정·비밀번호가 없고, CI 에 넣을
 * secret 도 없다. 다른 강사가 필요한 테스트(소유권)는 {@link #signUpInstructor()} 로 한 명 더 만든다.
 *
 * <p>실제 아동 이름은 쓰지 않는다. 공개 레포이고 테스트 로그도 누구나 볼 수 있다.
 */
public class TestFixtures {

    public static final String CLASSROOM_A1 = "햇살반";
    public static final String CLASSROOM_B1 = "바람반";
    public static final String NAMESAKE = "김하늘";
    public static final String CHILD_B1_1 = "이바다";
    public static final String INSTRUCTOR_NAME = "테스트 강사";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvcTester mvc;

    private Instructor instructor;

    TestFixtures(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    /** 이 픽스처가 학급·아동 요청에 쓰는 강사. 컨텍스트마다 처음 부를 때 한 번 가입한다. */
    public synchronized Instructor instructor() {
        if (instructor == null) {
            instructor = signUpInstructor();
        }
        return instructor;
    }

    /** 새 강사를 가입시키고 로그인한다. 이메일·비밀번호는 부를 때마다 새로 만든다. */
    public Instructor signUpInstructor() {
        Credentials credentials = newCredentials();
        UUID userId = signUp(credentials);
        return new Instructor(
                userId,
                credentials.email(),
                credentials.password(),
                login(credentials.email(), credentials.password()));
    }

    /** 가입만 하고 userId 를 돌려준다. */
    public UUID signUp(Credentials credentials) {
        MvcTestResult result =
                signUpRequest(credentials.email(), credentials.password(), INSTRUCTOR_NAME);
        assertCreated(result, "강사 가입");
        return UUID.fromString(JsonPath.read(body(result), "$.data.userId"));
    }

    /** 가입 요청(POST /api/v1/users)을 그대로 보낸다. 결과는 확인하지 않는다. */
    public MvcTestResult signUpRequest(String email, String password, String name) {
        return postPublicJson(
                "/api/v1/users", Map.of("email", email, "password", password, "name", name));
    }

    /** 로그인해 토큰 원문을 받는다. */
    public String login(String email, String password) {
        MvcTestResult result = loginRequest(email, password);
        assertThat(result).as("로그인").hasStatusOk();
        return JsonPath.read(body(result), "$.data.accessToken");
    }

    /** 로그인 요청(POST /api/v1/auth/sessions)을 그대로 보낸다. 결과는 확인하지 않는다. */
    public MvcTestResult loginRequest(String email, String password) {
        return postPublicJson(
                "/api/v1/auth/sessions", Map.of("email", email, "password", password));
    }

    /** 가입에 쓸 새 이메일·비밀번호. 비밀번호는 가입 규칙(8자 이상)을 넘는 무작위 값이다. */
    public static Credentials newCredentials() {
        String id = UUID.randomUUID().toString();
        return new Credentials("instructor-" + id + "@example.com", "pw-" + id);
    }

    /** 표준 데이터(A1·B1, A1-1·A1-2·B1-1)를 새로 만든다. */
    public Standard createStandard() {
        UUID a1 = createClassroom(CLASSROOM_A1);
        UUID b1 = createClassroom(CLASSROOM_B1);
        return new Standard(
                a1,
                b1,
                createChild(a1, NAMESAKE),
                createChild(a1, NAMESAKE),
                createChild(b1, CHILD_B1_1));
    }

    public UUID createClassroom(String name) {
        return createClassroom(instructor(), name);
    }

    public UUID createClassroom(Instructor owner, String name) {
        MvcTestResult result = postJson(owner, "/api/v1/classrooms", Map.of("name", name));
        assertCreated(result, "학급 " + name);
        return UUID.fromString(JsonPath.read(body(result), "$.data.classId"));
    }

    public UUID createChild(UUID classId, String displayName) {
        return createChild(instructor(), classId, displayName);
    }

    public UUID createChild(Instructor owner, UUID classId, String displayName) {
        MvcTestResult result =
                postJson(
                        owner,
                        "/api/v1/classrooms/" + classId + "/children",
                        Map.of("displayName", displayName));
        assertCreated(result, "아동 " + displayName);
        return UUID.fromString(JsonPath.read(body(result), "$.data.childId"));
    }

    /** 기본 강사로 GET 한다. */
    public MvcTestResult get(String uri, Object... uriVariables) {
        return get(instructor(), uri, uriVariables);
    }

    public MvcTestResult get(Instructor as, String uri, Object... uriVariables) {
        return bearer(mvc.get().uri(uri, uriVariables), as.accessToken()).exchange();
    }

    /**
     * 프론트와 같은 방식으로 JSON 을 POST 한다. GET /api/v1/csrf 응답 본문의 토큰을 헤더에 싣고, 서버가 심은 쿠키와 기본 강사의 토큰을 함께
     * 보낸다.
     *
     * <p>기능 테스트에서 변경 요청을 보낼 때도 이걸 쓴다({@code csrf()} 후처리기 대신).
     */
    public MvcTestResult postJson(String uri, Object body) {
        return postJson(instructor(), uri, body);
    }

    public MvcTestResult postJson(Instructor as, String uri, Object body) {
        return postJsonText(as, uri, JSON.writeValueAsString(body));
    }

    /** {@link #postJson} 과 같지만 본문 문자열을 그대로 보낸다. null 값·키 없음·깨진 JSON 을 보낼 때 쓴다. */
    public MvcTestResult postJsonText(String uri, String json) {
        return postJsonText(instructor(), uri, json);
    }

    public MvcTestResult postJsonText(Instructor as, String uri, String json) {
        return bearer(jsonPost(uri, json), as.accessToken()).exchange();
    }

    /** 로그인 없이 부르는 공개 경로(가입·로그인)에 CSRF 만 실어 POST 한다. */
    public MvcTestResult postPublicJson(String uri, Object body) {
        return postPublicJsonText(uri, JSON.writeValueAsString(body));
    }

    /** {@link #postPublicJson} 과 같지만 본문 문자열을 그대로 보낸다. */
    public MvcTestResult postPublicJsonText(String uri, String json) {
        return jsonPost(uri, json).exchange();
    }

    /** 기본 강사의 토큰을 싣는다. 요청을 직접 만들어야 하는 테스트(헤더·쿠키를 바꿔 보는 보안 검사)가 쓴다. */
    public MockMvcTester.MockMvcRequestBuilder authorized(
            MockMvcTester.MockMvcRequestBuilder request) {
        return bearer(request, instructor().accessToken());
    }

    public static MockMvcTester.MockMvcRequestBuilder bearer(
            MockMvcTester.MockMvcRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** GET /api/v1/csrf 로 헤더 이름·토큰과 쿠키를 받는다. */
    public CsrfCredentials fetchCsrf() {
        MvcTestResult result = mvc.get().uri("/api/v1/csrf").exchange();
        assertThat(result)
                .as("GET /api/v1/csrf — TestFixtures 는 @LocalProfileIntegrationTest 에서만 쓸 수 있다")
                .hasStatusOk();
        Cookie[] cookies = result.getResponse().getCookies();
        assertThat(cookies).as("GET /api/v1/csrf 가 심은 CSRF 쿠키").isNotEmpty();
        String json = body(result);
        return new CsrfCredentials(
                JsonPath.read(json, "$.data.headerName"),
                JsonPath.read(json, "$.data.token"),
                cookies);
    }

    public static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MockMvcTester.MockMvcRequestBuilder jsonPost(String uri, String json) {
        CsrfCredentials csrf = fetchCsrf();
        return mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header(csrf.headerName(), csrf.token())
                .cookie(csrf.cookies());
    }

    private static void assertCreated(MvcTestResult result, String what) {
        assertThat(result)
                .as("%s 생성 — TestFixtures 는 @LocalProfileIntegrationTest 에서만 쓸 수 있다", what)
                .hasStatus2xxSuccessful();
    }

    /** 표준 데이터의 ID. 이름은 위 상수를 쓴다. */
    public record Standard(UUID a1, UUID b1, UUID a1Child1, UUID a1Child2, UUID b1Child1) {}

    /** 변경 요청에 실을 CSRF 값. 헤더에는 응답 본문의 토큰을, 쿠키에는 서버가 심은 쿠키를 그대로 싣는다. */
    public record CsrfCredentials(String headerName, String token, Cookie[] cookies) {}

    /** 가입에 쓰는 이메일·비밀번호. */
    public record Credentials(String email, String password) {}

    /** 가입·로그인한 테스트 강사. accessToken 은 로그인 응답의 토큰 원문이다. */
    public record Instructor(UUID userId, String email, String password, String accessToken) {}
}
