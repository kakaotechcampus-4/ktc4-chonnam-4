package com.neuringo.neuringobe;

import static com.neuringo.neuringobe.TestFixtures.NAMESAKE;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 학급·아동 API 의 공통 약속. 기능 동작이 아니라 격리와 DB 제약을 본다.
 *
 * <ul>
 *   <li>학급 격리: 학급의 아동 목록에는 그 학급 아동만 나온다.
 *   <li>강사 격리(VS-001 "다른 강사의 학급·아동은 목록과 상세에서 모두 조회되지 않는다", VS-002 "다른 강사의 학급에는 아동을 등록할 수 없다"): 다른
 *       강사의 학급은 목록에 없고, 상세·아동 목록·아동 등록은 없는 학급과 같은 404 다. 등록은 저장되지 않는다.
 *   <li>존재 여부 비노출(VS-001 "권한 밖 … ID는 존재 여부가 드러나지 않도록"): 다른 강사의 학급과 없는 학급의 오류 본문은 ID·traceId 말고 같다.
 *   <li>동명이인: 같은 학급에 이름이 같은 아동이 있어도 각자 다른 아동으로 저장되고, 이름은 입력한 그대로 남는다.
 *   <li>이름 길이 합의: 학급·아동 이름은 한글 100자까지 그대로 저장되고(DB VARCHAR(100)), 101자는 422 로 거절되어 저장되지 않는다(요청 검증
 *       {@code @Size(max = 100)}). 한쪽만 바뀌면 100자가 DB 에서 500 으로 터지거나 101자가 저장된다.
 * </ul>
 *
 * <p>HTTP 로만 확인한다. Service 계층 리팩터링 뒤에도 API 응답이 같으면 그대로 통과해야 한다.
 */
@LocalProfileIntegrationTest
class ClassroomChildIntegrationTest {

    private static final String HUNDRED_KOREAN = "가".repeat(100);
    private static final String HUNDRED_ONE_KOREAN = "가".repeat(101);

    @Autowired private TestFixtures fixtures;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void listsOnlyChildrenOfTheRequestedClassroom() {
        TestFixtures.Standard data = fixtures.createStandard();

        childrenOf(data.a1())
                .extractingPath("$.data[*].childId")
                .asArray()
                .containsExactlyInAnyOrder(data.a1Child1().toString(), data.a1Child2().toString());
        childrenOf(data.b1())
                .extractingPath("$.data[*].childId")
                .asArray()
                .containsExactly(data.b1Child1().toString());
    }

    @Test
    void listsOnlyTheInstructorsOwnClassrooms() {
        TestFixtures.Instructor other = fixtures.signUpInstructor();
        UUID mine = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        UUID theirs = fixtures.createClassroom(other, TestFixtures.CLASSROOM_A1);

        List<String> myList = classIdsIn(fixtures.get("/api/v1/classrooms"));
        List<String> theirList = classIdsIn(fixtures.get(other, "/api/v1/classrooms"));

        assertThat(myList).contains(mine.toString()).doesNotContain(theirs.toString());
        assertThat(theirList).containsExactly(theirs.toString());
        assertThat(
                        JsonPath.<List<String>>read(
                                TestFixtures.body(fixtures.get("/api/v1/classrooms")),
                                "$.data[*].instructorId"))
                .containsOnly(fixtures.instructor().userId().toString());
    }

    @Test
    void hidesOtherInstructorsClassroomAndChildrenAsNotFound() {
        TestFixtures.Instructor other = fixtures.signUpInstructor();
        UUID theirs = fixtures.createClassroom(other, TestFixtures.CLASSROOM_A1);
        fixtures.createChild(other, theirs, NAMESAKE);

        assertThat(fixtures.get("/api/v1/classrooms/{classId}", theirs))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("CLASSROOM_NOT_FOUND");
        assertThat(fixtures.get("/api/v1/classrooms/{classId}/children", theirs))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("CLASSROOM_NOT_FOUND");
    }

    @Test
    void answersOtherInstructorsClassroomLikeAMissingOne() {
        TestFixtures.Instructor other = fixtures.signUpInstructor();
        UUID theirs = fixtures.createClassroom(other, TestFixtures.CLASSROOM_A1);
        UUID missing = UUID.randomUUID();

        MvcTestResult forbidden = fixtures.get("/api/v1/classrooms/{classId}", theirs);
        MvcTestResult notFound = fixtures.get("/api/v1/classrooms/{classId}", missing);

        assertThat(forbidden.getResponse().getStatus())
                .isEqualTo(notFound.getResponse().getStatus());
        assertThat(withoutIds(TestFixtures.body(forbidden), theirs))
                .isEqualTo(withoutIds(TestFixtures.body(notFound), missing));
    }

    @Test
    void rejectsChildRegistrationInOtherInstructorsClassroomAndStoresNothing() {
        TestFixtures.Instructor other = fixtures.signUpInstructor();
        UUID theirs = fixtures.createClassroom(other, TestFixtures.CLASSROOM_A1);
        String name = NAMESAKE + " " + UUID.randomUUID();

        MvcTestResult result =
                fixtures.postJson(
                        "/api/v1/classrooms/" + theirs + "/children", Map.of("displayName", name));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM child WHERE display_name = ?",
                                Integer.class,
                                name))
                .isZero();
        assertThat(
                        JsonPath.<List<String>>read(
                                TestFixtures.body(
                                        fixtures.get(
                                                other,
                                                "/api/v1/classrooms/{classId}/children",
                                                theirs)),
                                "$.data[*].displayName"))
                .doesNotContain(name);
    }

    @Test
    void keepsNamesakesAsSeparateChildren() {
        TestFixtures.Standard data = fixtures.createStandard();

        assertThat(data.a1Child1()).isNotEqualTo(data.a1Child2());
        childrenOf(data.a1())
                .extractingPath("$.data[*].displayName")
                .asArray()
                .containsExactly(NAMESAKE, NAMESAKE);
    }

    @Test
    void classroomNameKeepsHundredKoreanCharactersAndRejectsMore() {
        UUID classId = fixtures.createClassroom(HUNDRED_KOREAN);

        assertThat(fixtures.get("/api/v1/classrooms/{classId}", classId))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.data.name")
                .asString()
                .isEqualTo(HUNDRED_KOREAN);
        assertThat(fixtures.postJson("/api/v1/classrooms", Map.of("name", HUNDRED_ONE_KOREAN)))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void childNameKeepsHundredKoreanCharactersAndRejectsMore() {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        fixtures.createChild(classId, HUNDRED_KOREAN);

        assertThat(
                        fixtures.postJson(
                                "/api/v1/classrooms/" + classId + "/children",
                                Map.of("displayName", HUNDRED_ONE_KOREAN)))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        childrenOf(classId)
                .extractingPath("$.data[*].displayName")
                .asArray()
                .containsExactly(HUNDRED_KOREAN);
    }

    private AbstractJsonContentAssert<?> childrenOf(UUID classId) {
        return assertThat(fixtures.get("/api/v1/classrooms/{classId}/children", classId))
                .hasStatusOk()
                .bodyJson();
    }

    private static List<String> classIdsIn(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return JsonPath.read(TestFixtures.body(result), "$.data[*].classId");
    }

    /** 응답마다 달라지는 값(요청한 학급 ID, traceId)만 지운다. 나머지가 같으면 두 경우를 구분할 수 없다. */
    private static String withoutIds(String body, UUID classId) {
        return body.replace(classId.toString(), "{classId}")
                .replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"\"");
    }
}
