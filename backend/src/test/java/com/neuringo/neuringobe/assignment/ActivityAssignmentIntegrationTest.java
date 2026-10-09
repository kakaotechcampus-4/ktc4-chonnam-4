package com.neuringo.neuringobe.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 활동 배정(ADR 2026-10-03 D4). 서버가 승인 문항을 골라 활동과 함께 한 번에 만들고, 같은 요청 키의 재전송과 같은 목표의 중복 배정을 막는다. V6 시드
 * 문항이 있어 유형마다 승인 문항이 하나 이상 있다.
 */
@LocalProfileIntegrationTest
class ActivityAssignmentIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired private TestFixtures fixtures;

    private TestFixtures.Instructor instructor;
    private UUID childId;
    private UUID goalId;

    @BeforeEach
    void setUp() {
        instructor = fixtures.instructor();
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        childId = fixtures.createChild(classId, TestFixtures.NAMESAKE);
        goalId = createGoal(childId, "친구 감정 알아보기");
    }

    @Test
    void assignsActivityWithServerPickedQuizItemsInTypeOrder() {
        MvcTestResult result = assign(childId, goalId, UUID.randomUUID());

        assertThat(result).hasStatus(HttpStatus.CREATED);
        String activityId = JsonPath.read(TestFixtures.body(result), "$.data.activityId");
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/activities/" + activityId);
        assertThat(JsonPath.<String>read(TestFixtures.body(result), "$.data.status"))
                .isEqualTo("NOT_STARTED");

        String detail = TestFixtures.body(fixtures.get("/api/v1/activities/{id}", activityId));
        assertThat(JsonPath.<List<String>>read(detail, "$.data.quizItems[*].quizType"))
                .containsExactly(
                        "SELF_EMOTION_SITUATION", "OTHER_EMOTION_SITUATION", "OTHER_EMOTION_IMAGE");
        assertThat(JsonPath.<List<Integer>>read(detail, "$.data.quizItems[*].questionOrder"))
                .containsExactly(1, 2, 3);
        assertThat(JsonPath.<Map<String, Object>>read(detail, "$.data"))
                .containsKeys("activity", "quizItems", "sessionSummary", "learningRecord");
        assertThat(JsonPath.<Object>read(detail, "$.data.sessionSummary")).isNull();
    }

    @Test
    void sameRequestKeyReturnsTheFirstActivityInsteadOfCreatingAnother() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = assign(childId, goalId, key);
        MvcTestResult retry = assign(childId, goalId, key);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.OK);
        String firstId = JsonPath.read(TestFixtures.body(first), "$.data.activityId");
        assertThat(JsonPath.<String>read(TestFixtures.body(retry), "$.data.activityId"))
                .isEqualTo(firstId);
        assertThat(activityIdsOf(childId)).containsExactly(firstId);
    }

    @Test
    void sameRequestKeyForAnotherGoalIsRejected() {
        UUID key = UUID.randomUUID();
        assign(childId, goalId, key);
        UUID otherGoal = createGoal(childId, "도움 요청하기");

        assertError(assign(childId, otherGoal, key), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void sameKeySentConcurrentlyForDifferentChildrenCreatesOnlyOneAndAnswers409() throws Exception {
        // 잠금은 아동별이라 서로 다른 아동의 요청은 동시에 "키 없음"을 볼 수 있다. 그래도 500 이 아니라 순차 요청과 같은 409 여야 한다(Codex 검토
        // P2).
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_B1);
        List<UUID[]> targets = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            UUID child = fixtures.createChild(classId, "동시 배정 아동 " + i);
            targets.add(new UUID[] {child, createGoal(child, "친구 감정 알아보기")});
        }
        UUID key = UUID.randomUUID();

        List<MvcTestResult> results =
                concurrently(i -> assign(targets.get(i)[0], targets.get(i)[1], key));

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status != 201).containsOnly(409);
        assertThat(results)
                .filteredOn(r -> r.getResponse().getStatus() == 409)
                .allSatisfy(
                        r ->
                                assertThat(
                                                JsonPath.<String>read(
                                                        TestFixtures.body(r), "$.error.code"))
                                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void sameGoalIsNotAssignedTwiceWhileTheFirstHasNotStarted() {
        assign(childId, goalId, UUID.randomUUID());

        assertError(
                assign(childId, goalId, UUID.randomUUID()),
                HttpStatus.CONFLICT,
                "DUPLICATE_ASSIGNMENT");
        assertThat(activityIdsOf(childId)).hasSize(1);
    }

    @Test
    void sameGoalContentSavedAgainIsStillADuplicate() {
        assign(childId, goalId, UUID.randomUUID());
        // 강사가 같은 목표를 다시 고르면 목표 행은 새로 생기지만 내용은 같다(ADR 2026-10-03 D6).
        UUID sameContent = createGoal(childId, "친구 감정 알아보기");

        assertError(
                assign(childId, sameContent, UUID.randomUUID()),
                HttpStatus.CONFLICT,
                "DUPLICATE_ASSIGNMENT");
        assertThat(activityIdsOf(childId)).hasSize(1);
    }

    @Test
    void differentGoalContentCanBeAssignedAlongside() {
        assign(childId, goalId, UUID.randomUUID());
        UUID otherContent = createGoal(childId, "도움이 필요할 때 말로 요청한다");

        assertThat(assign(childId, otherContent, UUID.randomUUID())).hasStatus(HttpStatus.CREATED);
        assertThat(activityIdsOf(childId)).hasSize(2);
    }

    @Test
    void otherInstructorCannotAssignOrReadAndSeesNotFound() {
        TestFixtures.Instructor other = fixtures.signUpInstructor();
        MvcTestResult created = assign(childId, goalId, UUID.randomUUID());
        String activityId = JsonPath.read(TestFixtures.body(created), "$.data.activityId");

        assertError(
                fixtures.postJsonWithKey(
                        other,
                        "/api/v1/activities",
                        Map.of("childId", childId, "goalId", goalId),
                        UUID.randomUUID()),
                HttpStatus.NOT_FOUND,
                "CHILD_NOT_FOUND");
        // 남의 활동은 없는 활동과 같은 code 다. code 가 다르면 그 ID 의 활동이 있다는 게 드러난다.
        assertError(
                fixtures.get(other, "/api/v1/activities/{id}", activityId),
                HttpStatus.NOT_FOUND,
                "ACTIVITY_NOT_FOUND");
        assertError(
                fixtures.get(other, "/api/v1/activities/{id}/quiz-items", activityId),
                HttpStatus.NOT_FOUND,
                "ACTIVITY_NOT_FOUND");
        assertError(
                fixtures.get(other, "/api/v1/children/{id}", childId),
                HttpStatus.NOT_FOUND,
                "CHILD_NOT_FOUND");
    }

    @Test
    void goalOfAnotherChildIsHidden() {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_B1);
        UUID otherChild = fixtures.createChild(classId, TestFixtures.CHILD_B1_1);

        assertError(
                assign(otherChild, goalId, UUID.randomUUID()),
                HttpStatus.NOT_FOUND,
                "GOAL_NOT_FOUND");
    }

    @Test
    void requestWithoutKeyIsRejected() {
        MvcTestResult result =
                fixtures.postJson(
                        instructor,
                        "/api/v1/activities",
                        Map.of("childId", childId, "goalId", goalId));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(activityIdsOf(childId)).isEmpty();
    }

    @Test
    void childDetailShowsTheChildOfAnOwnedClassroom() {
        String body = TestFixtures.body(fixtures.get("/api/v1/children/{id}", childId));

        assertThat(JsonPath.<String>read(body, "$.data.childId")).isEqualTo(childId.toString());
        assertThat(JsonPath.<String>read(body, "$.data.displayName"))
                .isEqualTo(TestFixtures.NAMESAKE);
    }

    /**
     * request(i) 를 여러 스레드에서 한꺼번에 출발시킨다(ConcurrentRegistrationIntegrationTest 와 같은 방식). 돌려주는 목록은 i
     * 순서다.
     */
    private static List<MvcTestResult> concurrently(IntFunction<MvcTestResult> request)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                int index = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return request.apply(index);
                                }));
            }
            start.countDown();
            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID createGoal(UUID child, String title) {
        MvcTestResult result =
                fixtures.postJson(
                        instructor,
                        "/api/v1/children/" + child + "/learning-goals",
                        Map.of("title", title));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(TestFixtures.body(result), "$.data.goalId"));
    }

    private MvcTestResult assign(UUID child, UUID goal, UUID key) {
        return fixtures.postJsonWithKey(
                instructor, "/api/v1/activities", Map.of("childId", child, "goalId", goal), key);
    }

    private List<String> activityIdsOf(UUID child) {
        return JsonPath.read(
                TestFixtures.body(fixtures.get("/api/v1/children/{id}/activities", child)),
                "$.data[*].activityId");
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(TestFixtures.body(result), "$.error.code"))
                .isEqualTo(code);
    }
}
