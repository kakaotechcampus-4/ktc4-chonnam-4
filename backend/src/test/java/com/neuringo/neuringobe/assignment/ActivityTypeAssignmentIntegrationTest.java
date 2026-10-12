package com.neuringo.neuringobe.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 활동 유형별 배정 규칙. 최초 결합은 아동당 한 번이고, 그것을 마친 뒤에만 독립 퀴즈를 배정한다. 독립 퀴즈는 목표 없이 유형별 구성(내 감정 10 / 상대 감정 상황 7
 * + 이미지 3)대로 문항이 붙는다. 시드 문항만으로는 10칸을 못 채워 테스트마다 승인 문항을 더 넣는다. 최초 결합 완료는 역할극 API 가 생기기 전이라 DB 상태를 직접
 * 바꿔 만든다.
 */
@LocalProfileIntegrationTest
class ActivityTypeAssignmentIntegrationTest {

    private static final Map<String, Object> GOAL = Map.of("title", "친구 감정 알아보기");

    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private QuizItemRepository items;

    private TestFixtures.Instructor instructor;
    private UUID childId;

    @BeforeEach
    void setUp() {
        instructor = fixtures.instructor();
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        childId = fixtures.createChild(classId, TestFixtures.NAMESAKE);
        saveApprovedItems(QuizType.SELF_EMOTION_SITUATION, 10);
        saveApprovedItems(QuizType.OTHER_EMOTION_SITUATION, 7);
        saveApprovedItems(QuizType.OTHER_EMOTION_IMAGE, 3);
    }

    @Test
    void independentQuizWaitsUntilTheFirstActivityIsCompleted() {
        assertError(
                assign(childId, null, "SELF_EMOTION_QUIZ"),
                HttpStatus.CONFLICT,
                "FIRST_ACTIVITY_NOT_COMPLETED");

        assignFirst(childId);
        assertError(
                assign(childId, null, "OTHER_EMOTION_QUIZ"),
                HttpStatus.CONFLICT,
                "FIRST_ACTIVITY_NOT_COMPLETED");
    }

    @Test
    void firstCombinedIsAssignedOncePerChildEvenWithAnotherGoal() {
        assignFirst(childId);

        assertError(
                assign(childId, Map.of("title", "도움이 필요할 때 말로 요청한다"), null),
                HttpStatus.CONFLICT,
                "FIRST_ACTIVITY_EXISTS");
    }

    @Test
    void selfEmotionQuizHasNoGoalAndTenSelfItems() {
        completeFirst(childId);

        MvcTestResult result = assign(childId, null, "SELF_EMOTION_QUIZ");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        String body = TestFixtures.body(result);
        assertThat(JsonPath.<String>read(body, "$.data.activityType"))
                .isEqualTo("SELF_EMOTION_QUIZ");
        assertThat(JsonPath.<Object>read(body, "$.data.goalId")).isNull();
        assertThat(quizTypesOf(JsonPath.read(body, "$.data.activityId")))
                .containsExactlyElementsOf(
                        Collections.nCopies(10, QuizType.SELF_EMOTION_SITUATION.name()));
    }

    @Test
    void otherEmotionQuizHasSevenSituationsThenThreeImages() {
        completeFirst(childId);

        String body = TestFixtures.body(assign(childId, null, "OTHER_EMOTION_QUIZ"));

        List<String> types = quizTypesOf(JsonPath.read(body, "$.data.activityId"));
        assertThat(types.subList(0, 7))
                .containsOnly(QuizType.OTHER_EMOTION_SITUATION.name())
                .hasSize(7);
        assertThat(types.subList(7, types.size()))
                .containsOnly(QuizType.OTHER_EMOTION_IMAGE.name())
                .hasSize(3);
    }

    @Test
    void sameIndependentQuizIsNotAssignedTwiceWhileNotStarted() {
        completeFirst(childId);
        assign(childId, null, "SELF_EMOTION_QUIZ");

        assertError(
                assign(childId, null, "SELF_EMOTION_QUIZ"),
                HttpStatus.CONFLICT,
                "DUPLICATE_ASSIGNMENT");
        assertThat(assign(childId, null, "OTHER_EMOTION_QUIZ")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void sameRequestKeyForAnIndependentQuizReturnsTheFirstActivity() {
        completeFirst(childId);
        UUID key = UUID.randomUUID();

        MvcTestResult first = assign(childId, null, "SELF_EMOTION_QUIZ", key);
        MvcTestResult retry = assign(childId, null, "SELF_EMOTION_QUIZ", key);

        assertThat(retry).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<String>read(TestFixtures.body(retry), "$.data.activityId"))
                .isEqualTo(JsonPath.read(TestFixtures.body(first), "$.data.activityId"));
        assertError(
                assign(childId, null, "OTHER_EMOTION_QUIZ", key),
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void goalMustMatchTheTypeAndRoleplayIsNotAvailableYet() {
        assertError(
                assign(childId, null, "FIRST_COMBINED"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "GOAL_REQUIRED");

        completeFirst(childId);
        assertError(
                assign(childId, GOAL, "SELF_EMOTION_QUIZ"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "GOAL_NOT_ALLOWED");
        assertError(
                assign(childId, GOAL, "ROLEPLAY"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "ACTIVITY_TYPE_NOT_AVAILABLE");
        // 거절된 독립 퀴즈 요청은 목표를 남기지 않는다. 남은 목표는 최초 결합의 것 하나다.
        assertThat(
                        JsonPath.<List<String>>read(
                                TestFixtures.body(
                                        fixtures.get(
                                                "/api/v1/children/{id}/learning-goals", childId)),
                                "$.data[*].goalId"))
                .hasSize(1);
    }

    @Test
    void omittedTypeMeansFirstCombined() {
        String body =
                TestFixtures.body(
                        fixtures.postJsonWithKey(
                                instructor,
                                "/api/v1/activities",
                                Map.of("childId", childId, "goal", GOAL),
                                UUID.randomUUID()));

        assertThat(JsonPath.<String>read(body, "$.data.activityType")).isEqualTo("FIRST_COMBINED");
    }

    /** 역할극 API 가 없어 최초 결합을 끝낼 길이 없다. 배정한 뒤 상태만 완료로 바꾼다. */
    private void completeFirst(UUID child) {
        UUID activityId = assignFirst(child);
        jdbc.update(
                "update activity set status = 'COMPLETED', completed_at = now() where activity_id = ?",
                activityId);
    }

    private UUID assignFirst(UUID child) {
        MvcTestResult result = assign(child, GOAL, "FIRST_COMBINED");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(TestFixtures.body(result), "$.data.activityId"));
    }

    private MvcTestResult assign(UUID child, Map<String, Object> goal, String type) {
        return assign(child, goal, type, UUID.randomUUID());
    }

    private MvcTestResult assign(UUID child, Map<String, Object> goal, String type, UUID key) {
        Map<String, Object> body = new HashMap<>();
        body.put("childId", child);
        body.put("goal", goal);
        body.put("activityType", type);
        return fixtures.postJsonWithKey(instructor, "/api/v1/activities", body, key);
    }

    private void saveApprovedItems(QuizType type, int count) {
        for (int i = 0; i < count; i++) {
            items.save(
                    new QuizItem(
                            UUID.randomUUID(),
                            type,
                            Emotion.JOY,
                            "어떤 기분일까?",
                            type == QuizType.OTHER_EMOTION_IMAGE
                                    ? "/quiz-images/sample-anger.svg"
                                    : null,
                            List.of("기쁨", "슬픔"),
                            "기쁨",
                            List.of("기쁨"),
                            List.of(
                                    new QuizItem.HintSpec("OBSERVABLE_FACE_CUE", "얼굴을 살펴봐요."),
                                    new QuizItem.HintSpec("CHOICE_REDUCTION", "둘 중 골라봐요.")),
                            1));
        }
    }

    private List<String> quizTypesOf(String activityId) {
        return JsonPath.read(
                TestFixtures.body(fixtures.get("/api/v1/activities/{id}", activityId)),
                "$.data.quizItems[*].quizType");
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(TestFixtures.body(result), "$.error.code"))
                .isEqualTo(code);
    }
}
