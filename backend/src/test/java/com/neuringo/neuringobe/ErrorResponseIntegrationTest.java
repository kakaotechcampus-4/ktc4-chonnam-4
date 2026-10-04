package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 실패한 요청은 아무것도 바꾸지 않고, 요청 본문으로 서버가 정할 값을 바꿀 수 없다. 응답 모양은 API 명세 검사(contract.ApiContractTest)가 보고,
 * 여기서는 부수 효과와 조작을 본다.
 *
 * <ul>
 *   <li>없는 학급에 아동을 등록하면 404 이고 DB 에 아무것도 남지 않는다(VS-002 "다른 학급·없는 학급에는 등록할 수 없다").
 *   <li>아동 API 도 UUID 가 아닌 학급 ID 는 400, JSON 이 아닌 본문은 415, 깨진 JSON 은 400 이고 저장되지 않는다.
 *   <li>본문에 classId·childId·status·instructorId 를 넣어도 무시한다. 학급은 경로의 학급, 강사는 로그인한 강사, 상태는 ACTIVE, ID
 *       는 서버가 새로 만든다(권한 원칙 — 다른 학급·다른 강사로 바꿔치기할 수 없다).
 *   <li>오류 본문에 예외 클래스 이름·스택·SQL 같은 내부 정보가 나오지 않는다. 인증 실패(401)·이메일 중복(409)도 같다.
 *   <li>응답마다 새 traceId 를 준다(06 DoD "운영에 필요한 추적 ID").
 * </ul>
 */
@LocalProfileIntegrationTest
class ErrorResponseIntegrationTest {

    private static final List<String> INTERNAL_DETAILS =
            List.of(
                    "Exception",
                    "at com.",
                    "org.springframework",
                    "tools.jackson",
                    "SQL",
                    "Hibernate");

    @Autowired private MockMvcTester mvc;

    @Autowired private TestFixtures fixtures;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void rejectsChildForUnknownClassroomAndStoresNothing() {
        UUID unknownClassId = UUID.randomUUID();

        MvcTestResult result =
                fixtures.postJson(
                        "/api/v1/classrooms/" + unknownClassId + "/children",
                        Map.of("displayName", TestFixtures.NAMESAKE));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM child WHERE class_id = ?",
                                Integer.class,
                                unknownClassId))
                .isZero();
    }

    @Test
    void childListRejectsMalformedClassroomId() {
        assertThat(fixtures.get("/api/v1/classrooms/not-a-uuid/children"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void childRegistrationRejectsMalformedClassroomId() {
        assertThat(
                        fixtures.postJson(
                                "/api/v1/classrooms/not-a-uuid/children",
                                Map.of("displayName", TestFixtures.NAMESAKE)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void rejectsNonJsonChildBodyAndStoresNothing() {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        TestFixtures.CsrfCredentials csrf = fixtures.fetchCsrf();

        MvcTestResult result =
                fixtures.authorized(
                                mvc.post()
                                        .uri("/api/v1/classrooms/{classId}/children", classId)
                                        .contentType(MediaType.TEXT_PLAIN)
                                        .content("displayName=" + TestFixtures.NAMESAKE)
                                        .header(csrf.headerName(), csrf.token())
                                        .cookie(csrf.cookies()))
                        .exchange();

        assertThat(result)
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(childNamesOf(classId)).isEmpty();
    }

    @Test
    void rejectsMalformedChildJsonAndStoresNothing() {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);

        MvcTestResult result =
                fixtures.postJsonText(
                        "/api/v1/classrooms/" + classId + "/children", "{\"displayName\":");

        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("INVALID_REQUEST");
        assertThat(childNamesOf(classId)).isEmpty();
    }

    @Test
    void ignoresServerOwnedFieldsInClassroomBody() {
        UUID requestedId = UUID.randomUUID();
        TestFixtures.Instructor other = fixtures.signUpInstructor();

        MvcTestResult result =
                fixtures.postJson(
                        "/api/v1/classrooms",
                        Map.of(
                                "name",
                                TestFixtures.CLASSROOM_A1,
                                "classId",
                                requestedId.toString(),
                                "status",
                                "ARCHIVED",
                                "instructorId",
                                other.userId().toString()));

        assertThat(result).hasStatusOk();
        String json = TestFixtures.body(result);
        assertThat(JsonPath.<String>read(json, "$.data.classId"))
                .isNotEqualTo(requestedId.toString());
        assertThat(JsonPath.<String>read(json, "$.data.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<String>read(json, "$.data.instructorId"))
                .isEqualTo(fixtures.instructor().userId().toString());
        assertThat(
                        JsonPath.<List<String>>read(
                                TestFixtures.body(fixtures.get(other, "/api/v1/classrooms")),
                                "$.data[*].classId"))
                .as("본문의 instructorId(다른 강사) 목록")
                .isEmpty();
    }

    @Test
    void registersChildOnlyInTheClassroomFromThePath() {
        UUID a1 = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        UUID b1 = fixtures.createClassroom(TestFixtures.CLASSROOM_B1);
        UUID requestedChildId = UUID.randomUUID();

        MvcTestResult result =
                fixtures.postJson(
                        "/api/v1/classrooms/" + a1 + "/children",
                        Map.of(
                                "displayName",
                                TestFixtures.NAMESAKE,
                                "classId",
                                b1.toString(),
                                "childId",
                                requestedChildId.toString(),
                                "status",
                                "REMOVED"));

        assertThat(result).hasStatusOk();
        String json = TestFixtures.body(result);
        assertThat(JsonPath.<String>read(json, "$.data.classId")).isEqualTo(a1.toString());
        assertThat(JsonPath.<String>read(json, "$.data.childId"))
                .isNotEqualTo(requestedChildId.toString());
        assertThat(JsonPath.<String>read(json, "$.data.status")).isEqualTo("ACTIVE");
        assertThat(childNamesOf(b1)).as("본문의 classId(B1) 학급").isEmpty();
    }

    static Stream<Arguments> failingRequests() {
        return Stream.<Arguments>of(
                failing(
                        "깨진 JSON",
                        t -> t.fixtures.postJsonText("/api/v1/classrooms", "{\"name\":")),
                failing("UUID 가 아닌 학급 ID", t -> t.fixtures.get("/api/v1/classrooms/not-a-uuid")),
                failing("없는 학급", t -> t.fixtures.get("/api/v1/classrooms/{id}", UUID.randomUUID())),
                failing("없는 경로", t -> t.fixtures.get("/api/v1/no-such-path")),
                failing(
                        "지원하지 않는 메서드",
                        t -> {
                            TestFixtures.CsrfCredentials csrf = t.fixtures.fetchCsrf();
                            return t.fixtures
                                    .authorized(
                                            t.mvc.delete()
                                                    .uri("/api/v1/classrooms")
                                                    .header(csrf.headerName(), csrf.token())
                                                    .cookie(csrf.cookies()))
                                    .exchange();
                        }),
                failing("토큰 없음", t -> t.mvc.get().uri("/api/v1/classrooms").exchange()),
                failing(
                        "틀린 토큰",
                        t ->
                                TestFixtures.bearer(
                                                t.mvc.get().uri("/api/v1/classrooms"),
                                                "not-a-real-token")
                                        .exchange()),
                failing(
                        "로그인 실패",
                        t ->
                                t.fixtures.loginRequest(
                                        t.fixtures.instructor().email(), "wrong-password")),
                failing(
                        "이메일 중복",
                        t ->
                                t.fixtures.signUpRequest(
                                        t.fixtures.instructor().email(),
                                        "another-password",
                                        TestFixtures.INSTRUCTOR_NAME)),
                failing(
                        "짧은 비밀번호",
                        t ->
                                t.fixtures.signUpRequest(
                                        TestFixtures.newCredentials().email(),
                                        "short",
                                        TestFixtures.INSTRUCTOR_NAME)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingRequests")
    void errorBodiesDoNotExposeInternals(
            String label, Function<ErrorResponseIntegrationTest, MvcTestResult> request) {
        MvcTestResult result = request.apply(this);

        assertThat(result.getResponse().getStatus()).as(label).isBetween(400, 499);
        String body = TestFixtures.body(result);
        assertThat(JsonPath.<String>read(body, "$.error.code")).isNotBlank();
        assertThat(body).doesNotContain(INTERNAL_DETAILS);
    }

    @Test
    void givesEveryResponseItsOwnTraceId() {
        Set<String> traceIds = new HashSet<>();

        for (int i = 0; i < 3; i++) {
            traceIds.add(
                    JsonPath.read(
                            TestFixtures.body(fixtures.get("/api/v1/classrooms")),
                            "$.meta.traceId"));
        }
        for (int i = 0; i < 2; i++) {
            MvcTestResult notFound = fixtures.get("/api/v1/classrooms/{id}", UUID.randomUUID());
            traceIds.add(JsonPath.read(TestFixtures.body(notFound), "$.error.traceId"));
        }
        MvcTestResult unauthenticated = mvc.get().uri("/api/v1/classrooms").exchange();
        traceIds.add(JsonPath.read(TestFixtures.body(unauthenticated), "$.error.traceId"));

        assertThat(traceIds).hasSize(6);
    }

    private List<String> childNamesOf(UUID classId) {
        String json =
                TestFixtures.body(fixtures.get("/api/v1/classrooms/{classId}/children", classId));
        return JsonPath.read(json, "$.data[*].displayName");
    }

    private static Arguments failing(
            String label, Function<ErrorResponseIntegrationTest, MvcTestResult> request) {
        return Arguments.of(label, request);
    }
}
