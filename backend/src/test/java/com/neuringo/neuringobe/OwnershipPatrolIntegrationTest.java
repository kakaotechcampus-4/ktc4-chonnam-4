package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 모든 강사용 API 의 소유권 순찰. "모든 강사 조회에 같은 소유권 규칙이 적용된다"를 API 하나하나에 대해 확인한다.
 *
 * <ul>
 *   <li>강사 B 가 강사 A 의 ID 로 요청하면 404 이고, 오류 code 가 없는 ID 로 요청했을 때와 같다. 남의 자원이 있다는 사실이 드러나지 않는다.
 *   <li>목록 API 는 B 에게 A 의 자원을 보여 주지 않는다.
 *   <li>서버에 등록된 API 가 {@link #CHECKED} 와 {@link #NOT_INSTRUCTOR_OWNED} 어디에도 없으면 실패한다. 새 API 를 만들면 둘
 *       중 한 곳에 적어야 하므로, 소유권 확인을 빠뜨린 API 가 조용히 들어오지 않는다.
 * </ul>
 */
@LocalProfileIntegrationTest
class OwnershipPatrolIntegrationTest {

    /** 소유권과 상관없는 API. 남의 ID 를 받지 않거나, 강사가 아닌 역할(아동·내부 처리)만 부를 수 있다. 각 줄에 이유를 적는다. */
    private static final Map<String, String> NOT_INSTRUCTOR_OWNED =
            Map.ofEntries(
                    Map.entry("GET /api/v1/csrf", "공개: CSRF 토큰"),
                    Map.entry("POST /api/v1/users", "공개: 가입"),
                    Map.entry("POST /api/v1/auth/sessions", "공개: 로그인"),
                    Map.entry("GET /api/v1/auth/session", "자기 세션만 본다"),
                    Map.entry("DELETE /api/v1/auth/session", "자기 세션만 끝낸다"),
                    Map.entry("POST /api/v1/users/me/withdrawal", "자기 계정만 탈퇴한다"),
                    Map.entry("POST /api/v1/classrooms", "새 학급을 자기 소유로 만든다. 남의 ID 를 받지 않는다"),
                    Map.entry("POST /api/v1/child-access-sessions", "아동 입장(코드)"),
                    Map.entry("DELETE /api/v1/child-access-sessions/current", "아동 사용 종료"),
                    Map.entry("PUT /api/v1/activity-quiz-items/{activityQuizId}/attempt", "아동만"),
                    Map.entry("POST /api/v1/activity-quiz-items/{activityQuizId}/hints", "아동만"),
                    Map.entry(
                            "PATCH /api/v1/activity-quiz-items/{activityQuizId}/attempt", "내부 처리만"),
                    // 역할극 음성 설정이 있을 때만 등록된다. 이 테스트 설정(local)에는 없다.
                    Map.entry("POST /api/v1/roleplay-sessions/{sessionId}/voice-inputs", "아동만"));

    /** ID 로 남의 자원을 가리킬 수 있는 강사용 API 와, 숨길 때 쓰는 오류 code. */
    private static final Map<String, String> CHECKED =
            Map.ofEntries(
                    Map.entry("GET /api/v1/classrooms/{classId}", "CLASSROOM_NOT_FOUND"),
                    Map.entry("DELETE /api/v1/classrooms/{classId}", "CLASSROOM_NOT_FOUND"),
                    Map.entry("GET /api/v1/classrooms/{classId}/children", "CLASSROOM_NOT_FOUND"),
                    Map.entry("POST /api/v1/classrooms/{classId}/children", "CLASSROOM_NOT_FOUND"),
                    Map.entry("GET /api/v1/children/{childId}", "CHILD_NOT_FOUND"),
                    Map.entry("DELETE /api/v1/children/{childId}", "CHILD_NOT_FOUND"),
                    Map.entry("POST /api/v1/children/{childId}/access-codes", "CHILD_NOT_FOUND"),
                    Map.entry("GET /api/v1/children/{childId}/activities", "CHILD_NOT_FOUND"),
                    Map.entry("GET /api/v1/children/{childId}/learning-goals", "CHILD_NOT_FOUND"),
                    Map.entry("POST /api/v1/children/{childId}/learning-goals", "CHILD_NOT_FOUND"),
                    Map.entry("GET /api/v1/learning-goals/{goalId}", "GOAL_NOT_FOUND"),
                    Map.entry("POST /api/v1/activities", "CHILD_NOT_FOUND"),
                    Map.entry("GET /api/v1/activities/{activityId}", "ACTIVITY_NOT_FOUND"),
                    Map.entry(
                            "GET /api/v1/activities/{activityId}/quiz-items", "ACTIVITY_NOT_FOUND"),
                    Map.entry(
                            "GET /api/v1/activities/{activityId}/quiz-result",
                            "ACTIVITY_NOT_FOUND"),
                    Map.entry("GET /api/v1/activities/{activityId}/status", "ACTIVITY_NOT_FOUND"),
                    Map.entry(
                            "GET /api/v1/activity-quiz-items/{activityQuizId}/attempt",
                            "ACTIVITY_QUIZ_NOT_FOUND"),
                    // 목록: B 에게 A 의 학급이 보이지 않는다(아래 listsOnlyOwnClassrooms).
                    Map.entry("GET /api/v1/classrooms", "LIST"));

    @Autowired private MockMvcTester mvc;
    @Autowired private TestFixtures fixtures;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private static Owned owned;
    private static TestFixtures.Instructor other;

    /** 강사 A 의 학급·아동·목표·활동·배정 문항을 한 번 만든다. B 는 이 ID 들로 요청한다. */
    @BeforeEach
    void createOwnedResources() {
        if (owned != null) {
            return;
        }
        TestFixtures.Instructor owner = fixtures.signUpInstructor();
        other = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, "순찰 학급");
        UUID childId = fixtures.createChild(owner, classId, "순찰 아동");
        MvcTestResult activity =
                fixtures.postJsonWithKey(
                        owner,
                        "/api/v1/activities",
                        Map.of("childId", childId, "goal", Map.of("title", "순찰 목표")),
                        UUID.randomUUID());
        assertThat(activity).as("A 의 활동 배정").hasStatus(HttpStatus.CREATED);
        UUID activityId =
                UUID.fromString(JsonPath.read(TestFixtures.body(activity), "$.data.activityId"));
        UUID goalId = UUID.fromString(JsonPath.read(TestFixtures.body(activity), "$.data.goalId"));
        MvcTestResult items = fixtures.get(owner, "/api/v1/activities/{id}/quiz-items", activityId);
        assertThat(items).as("A 의 배정 문항").hasStatusOk();
        UUID activityQuizId =
                UUID.fromString(
                        JsonPath.read(TestFixtures.body(items), "$.data[0].activityQuizId"));
        owned = new Owned(classId, childId, goalId, activityId, activityQuizId);
    }

    @Test
    void everyApiIsCheckedOrMarkedAsNotInstructorOwned() {
        Set<String> registered =
                mappings.getHandlerMethods().keySet().stream()
                        .flatMap(
                                info ->
                                        info.getMethodsCondition().getMethods().stream()
                                                .flatMap(
                                                        method ->
                                                                info
                                                                        .getPathPatternsCondition()
                                                                        .getPatternValues()
                                                                        .stream()
                                                                        .map(
                                                                                path ->
                                                                                        method + " "
                                                                                                + path)))
                        .filter(endpoint -> endpoint.contains(" /api/"))
                        .collect(Collectors.toCollection(TreeSet::new));

        Set<String> unlisted = new TreeSet<>(registered);
        unlisted.removeAll(CHECKED.keySet());
        unlisted.removeAll(NOT_INSTRUCTOR_OWNED.keySet());
        assertThat(unlisted)
                .as(
                        "새 API 다. 남의 ID 를 받으면 CHECKED 와 requests() 에, 아니면 이유와 함께 NOT_INSTRUCTOR_OWNED 에 적는다")
                .isEmpty();

        // 설정에 따라 등록되지 않는 API(역할극 음성)가 있어, 남은 항목 검사는 소유권을 확인하는 쪽만 한다.
        Set<String> stale = new TreeSet<>(CHECKED.keySet());
        stale.removeAll(registered);
        assertThat(stale).as("CHECKED 에 있지만 서버에 없는 API").isEmpty();

        Set<String> covered =
                requests()
                        .map(arguments -> (String) arguments.get()[0])
                        .collect(Collectors.toSet());
        covered.add("GET /api/v1/classrooms");
        assertThat(covered).as("CHECKED 의 API 마다 requests() 에 요청이 있다").isEqualTo(CHECKED.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void otherInstructorGetsTheSameNotFoundAsAnUnknownId(
            String endpoint,
            BiFunction<OwnershipPatrolIntegrationTest, Owned, MvcTestResult> request) {
        String hiddenAs = CHECKED.get(endpoint);

        MvcTestResult othersId = request.apply(this, owned);
        MvcTestResult unknownId = request.apply(this, Owned.unknown());

        assertThat(unknownId).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(unknownId).bodyJson().extractingPath("$.error.code").isEqualTo(hiddenAs);
        assertThat(othersId).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(othersId).bodyJson().extractingPath("$.error.code").isEqualTo(hiddenAs);
    }

    @Test
    void listsOnlyOwnClassrooms() {
        MvcTestResult list = fixtures.get(other, "/api/v1/classrooms");

        assertThat(list).hasStatusOk();
        List<String> classIds = JsonPath.read(TestFixtures.body(list), "$.data[*].classId");
        assertThat(classIds).doesNotContain(owned.classId().toString());
    }

    static Stream<Arguments> requests() {
        return Stream.of(
                get("GET /api/v1/classrooms/{classId}", "/api/v1/classrooms/%s", Owned::classId),
                delete(
                        "DELETE /api/v1/classrooms/{classId}",
                        "/api/v1/classrooms/%s",
                        Owned::classId),
                get(
                        "GET /api/v1/classrooms/{classId}/children",
                        "/api/v1/classrooms/%s/children",
                        Owned::classId),
                post(
                        "POST /api/v1/classrooms/{classId}/children",
                        "/api/v1/classrooms/%s/children",
                        Owned::classId,
                        "{\"displayName\":\"끼어든 아동\"}"),
                get("GET /api/v1/children/{childId}", "/api/v1/children/%s", Owned::childId),
                delete("DELETE /api/v1/children/{childId}", "/api/v1/children/%s", Owned::childId),
                post(
                        "POST /api/v1/children/{childId}/access-codes",
                        "/api/v1/children/%s/access-codes",
                        Owned::childId,
                        null),
                get(
                        "GET /api/v1/children/{childId}/activities",
                        "/api/v1/children/%s/activities",
                        Owned::childId),
                get(
                        "GET /api/v1/children/{childId}/learning-goals",
                        "/api/v1/children/%s/learning-goals",
                        Owned::childId),
                post(
                        "POST /api/v1/children/{childId}/learning-goals",
                        "/api/v1/children/%s/learning-goals",
                        Owned::childId,
                        "{\"title\":\"끼어든 목표\"}"),
                get(
                        "GET /api/v1/learning-goals/{goalId}",
                        "/api/v1/learning-goals/%s",
                        Owned::goalId),
                Arguments.of(
                        "POST /api/v1/activities",
                        (BiFunction<OwnershipPatrolIntegrationTest, Owned, MvcTestResult>)
                                (t, ids) ->
                                        t.send(
                                                t.mvc.post().uri("/api/v1/activities"),
                                                "{\"childId\":\""
                                                        + ids.childId()
                                                        + "\",\"goal\":{\"title\":\"끼어든 활동\"}}")),
                get(
                        "GET /api/v1/activities/{activityId}",
                        "/api/v1/activities/%s",
                        Owned::activityId),
                get(
                        "GET /api/v1/activities/{activityId}/quiz-items",
                        "/api/v1/activities/%s/quiz-items",
                        Owned::activityId),
                get(
                        "GET /api/v1/activities/{activityId}/quiz-result",
                        "/api/v1/activities/%s/quiz-result",
                        Owned::activityId),
                get(
                        "GET /api/v1/activities/{activityId}/status",
                        "/api/v1/activities/%s/status",
                        Owned::activityId),
                get(
                        "GET /api/v1/activity-quiz-items/{activityQuizId}/attempt",
                        "/api/v1/activity-quiz-items/%s/attempt",
                        Owned::activityQuizId));
    }

    private static Arguments get(
            String endpoint, String uri, java.util.function.Function<Owned, UUID> id) {
        return Arguments.of(
                endpoint,
                (BiFunction<OwnershipPatrolIntegrationTest, Owned, MvcTestResult>)
                        (t, ids) -> t.fixtures.get(other, uri.formatted(id.apply(ids))));
    }

    private static Arguments delete(
            String endpoint, String uri, java.util.function.Function<Owned, UUID> id) {
        return Arguments.of(
                endpoint,
                (BiFunction<OwnershipPatrolIntegrationTest, Owned, MvcTestResult>)
                        (t, ids) -> t.send(t.mvc.delete().uri(uri.formatted(id.apply(ids))), null));
    }

    private static Arguments post(
            String endpoint, String uri, java.util.function.Function<Owned, UUID> id, String json) {
        return Arguments.of(
                endpoint,
                (BiFunction<OwnershipPatrolIntegrationTest, Owned, MvcTestResult>)
                        (t, ids) -> t.send(t.mvc.post().uri(uri.formatted(id.apply(ids))), json));
    }

    /** 강사 B 로 변경 요청을 보낸다. 프론트처럼 CSRF 쿠키·헤더와 재전송 방지 키를 싣는다. */
    private MvcTestResult send(MockMvcTester.MockMvcRequestBuilder request, String json) {
        TestFixtures.CsrfCredentials csrf = fixtures.fetchCsrf();
        request.header(csrf.headerName(), csrf.token())
                .cookie(csrf.cookies())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return TestFixtures.bearer(request, other.accessToken()).exchange();
    }

    record Owned(UUID classId, UUID childId, UUID goalId, UUID activityId, UUID activityQuizId) {
        static Owned unknown() {
            return new Owned(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
    }
}
