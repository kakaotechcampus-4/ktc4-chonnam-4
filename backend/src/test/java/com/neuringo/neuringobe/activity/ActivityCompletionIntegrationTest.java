package com.neuringo.neuringobe.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.learningrecord.repository.LearningRecordRepository;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 활동 완료는 서버가 정한다. 독립 퀴즈는 마지막 문항이 확정될 때 완료·스탬프·학습 기록이 한 번만 생기고, 화면은 status 전용 조회로 결과를 읽는다. 최초 결합은 뒤에
 * 역할극이 남아 있어 퀴즈만으로는 완료되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class ActivityCompletionIntegrationTest {
    private static final String TEACHER = "teacher-completion";
    private static final String OTHER_TEACHER = "teacher-completion-other";

    @Autowired private MockMvc mvc;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private UserAccountRepository users;
    @Autowired private ChildRepository children;
    @Autowired private QuizItemRepository items;
    @Autowired private ActivityQuizRepository assignments;
    @Autowired private ActivityRepository activities;
    @Autowired private LearningRecordRepository records;

    private UUID classroomId;
    private UUID childId;
    private MockHttpSession childSession;

    @BeforeEach
    void setUp() throws Exception {
        TestInstructors.save(users, TEACHER);
        TestInstructors.save(users, OTHER_TEACHER);
        classroomId = UUID.randomUUID();
        classrooms.save(
                new Classroom(
                        classroomId, TestInstructors.id(TEACHER), "완료반", ClassroomStatus.ACTIVE));
        childId = newChild();
        childSession = enter(childId);
    }

    @Test
    void independentQuizCompletesOnceWhenLastItemIsFinalized() throws Exception {
        UUID activityId = createActivity(ActivityType.OTHER_EMOTION_QUIZ);
        UUID first = assign(activityId, 1);
        UUID second = assign(activityId, 2);

        answer(first, "슬픔", null);
        expectStatus(activityId, childSession, ActivityStatus.IN_PROGRESS);
        assertThat(records.existsByActivityId(activityId)).isFalse();

        answer(second, "슬픔", null);
        expectStatus(activityId, childSession, ActivityStatus.COMPLETED);
        Activity completed = activities.findById(activityId).orElseThrow();
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getRewardIssuedAt()).isEqualTo(completed.getCompletedAt());
        assertThat(records.countByActivityId(activityId)).isOne();

        // 응답을 잃어 같은 확정 요청을 다시 보내도 스탬프와 기록은 늘지 않는다.
        mvc.perform(attempt(second, "{\"firstResponse\":\"슬픔\"}")).andExpect(status().isOk());
        Activity resent = activities.findById(activityId).orElseThrow();
        assertThat(resent.getRewardIssuedAt()).isEqualTo(completed.getRewardIssuedAt());
        assertThat(records.countByActivityId(activityId)).isOne();
    }

    @Test
    void zeroAccuracyStillCompletesWithTheSameStamp() throws Exception {
        UUID activityId = createActivity(ActivityType.SELF_EMOTION_QUIZ);
        UUID only = assign(activityId, 1);

        answer(only, "기쁨", "기쁨");

        expectStatus(activityId, childSession, ActivityStatus.COMPLETED);
        assertThat(activities.findById(activityId).orElseThrow().getRewardIssuedAt()).isNotNull();
        assertThat(records.countByActivityId(activityId)).isOne();
    }

    @Test
    void firstCombinedQuizDoesNotCompleteTheActivity() throws Exception {
        UUID activityId = createActivity(ActivityType.FIRST_COMBINED);
        UUID only = assign(activityId, 1);

        answer(only, "슬픔", null);

        expectStatus(activityId, childSession, ActivityStatus.IN_PROGRESS);
        Activity activity = activities.findById(activityId).orElseThrow();
        assertThat(activity.getCompletedAt()).isNull();
        assertThat(activity.getRewardIssuedAt()).isNull();
        assertThat(records.existsByActivityId(activityId)).isFalse();
    }

    @Test
    void concurrentLastAnswersCompleteOnlyOnce() throws Exception {
        UUID activityId = createActivity(ActivityType.OTHER_EMOTION_QUIZ);
        List<UUID> assigned = List.of(assign(activityId, 1), assign(activityId, 2));
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(assigned.size())) {
            List<Future<Integer>> results = new ArrayList<>();
            for (UUID id : assigned) {
                Callable<Integer> task =
                        () -> {
                            start.await();
                            return mvc.perform(attempt(id, "{\"firstResponse\":\"슬픔\"}"))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
                        };
                results.add(workers.submit(task));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(201);
            }
        }

        expectStatus(activityId, childSession, ActivityStatus.COMPLETED);
        assertThat(records.countByActivityId(activityId)).isOne();
    }

    @Test
    void statusIsVisibleOnlyToTheChildAndTheirInstructor() throws Exception {
        UUID activityId = createActivity(ActivityType.OTHER_EMOTION_QUIZ);

        expectStatus(activityId, childSession, ActivityStatus.NOT_STARTED);
        mvc.perform(
                        get("/api/v1/activities/{id}/status", activityId)
                                .with(TestInstructors.instructor(TEACHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        MockHttpSession otherChild = enter(newChild());
        mvc.perform(get("/api/v1/activities/{id}/status", activityId).session(otherChild))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        mvc.perform(
                        get("/api/v1/activities/{id}/status", activityId)
                                .with(TestInstructors.instructor(OTHER_TEACHER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        mvc.perform(get("/api/v1/activities/{id}/status", UUID.randomUUID()).session(childSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
    }

    @Test
    void deletingTheChildRemovesTheLearningRecord() throws Exception {
        UUID activityId = createActivity(ActivityType.OTHER_EMOTION_QUIZ);
        answer(assign(activityId, 1), "슬픔", null);
        assertThat(records.existsByActivityId(activityId)).isTrue();

        mvc.perform(
                        delete("/api/v1/children/{id}", childId)
                                .with(TestInstructors.instructor(TEACHER))
                                .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(records.existsByActivityId(activityId)).isFalse();
        assertThat(activities.existsById(activityId)).isFalse();
    }

    private void answer(UUID assignmentId, String first, String finalResponse) throws Exception {
        String body =
                finalResponse == null
                        ? "{\"firstResponse\":\"" + first + "\"}"
                        : "{\"firstResponse\":\""
                                + first
                                + "\",\"finalResponse\":\""
                                + finalResponse
                                + "\"}";
        mvc.perform(attempt(assignmentId, body)).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.RequestBuilder attempt(
            UUID assignmentId, String body) {
        return put("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                .session(childSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void expectStatus(UUID activityId, MockHttpSession session, ActivityStatus expected)
            throws Exception {
        mvc.perform(get("/api/v1/activities/{id}/status", activityId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expected.name()));
    }

    private UUID newChild() {
        UUID id = UUID.randomUUID();
        children.save(new Child(id, classroomId, "완료 아동", ChildStatus.ACTIVE));
        return id;
    }

    private MockHttpSession enter(UUID child) throws Exception {
        String codeBody =
                mvc.perform(
                                post("/api/v1/children/{childId}/access-codes", child)
                                        .with(TestInstructors.instructor(TEACHER))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String code = JsonPath.read(codeBody, "$.data.accessCode");
        return (MockHttpSession)
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accessCode\":\"" + code + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    // 배정 API 는 문항을 무작위로 붙인다. 완료 흐름만 보려고 빈 활동을 저장한 뒤 정답이 정해진 문항을 직접 붙인다.
    private UUID createActivity(ActivityType type) {
        UUID goalId = type.requiresGoal() ? goal() : null;
        return activities
                .save(
                        new Activity(
                                UUID.randomUUID(),
                                childId,
                                type,
                                goalId,
                                UUID.randomUUID(),
                                Instant.now()))
                .getActivityId();
    }

    private UUID goal() {
        try {
            String body =
                    mvc.perform(
                                    post("/api/v1/children/{childId}/learning-goals", childId)
                                            .with(TestInstructors.instructor(TEACHER))
                                            .with(csrf())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"title\":\"감정 표현하기\"}"))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            return UUID.fromString(JsonPath.read(body, "$.data.goalId"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID assign(UUID activityId, int order) {
        UUID itemId = UUID.randomUUID();
        items.save(
                new QuizItem(
                        itemId,
                        QuizType.OTHER_EMOTION_SITUATION,
                        Emotion.SADNESS,
                        "어떤 감정일까요?",
                        null,
                        List.of("기쁨", "슬픔"),
                        "슬픔",
                        List.of("슬픔"),
                        List.of(
                                new QuizItem.HintSpec("OBSERVABLE_FACE_CUE", "얼굴을 살펴봐요."),
                                new QuizItem.HintSpec("CHOICE_REDUCTION", "둘 중 골라봐요.")),
                        1));
        UUID id = UUID.randomUUID();
        assignments.save(new ActivityQuiz(id, activityId, itemId, 1, order));
        return id;
    }
}
