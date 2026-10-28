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
import com.neuringo.neuringobe.learningrecord.domain.LearningRecord;
import com.neuringo.neuringobe.learningrecord.repository.LearningRecordRepository;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizAttemptRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
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
    @Autowired private QuizAttemptRepository attempts;
    @Autowired private QuizResultRepository results;

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
        UUID first = assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 1);
        UUID second = assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 2);

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
        UUID activityId = createActivity(ActivityType.OTHER_EMOTION_QUIZ);
        UUID only = assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 1);

        answer(only, "기쁨", "기쁨");

        expectStatus(activityId, childSession, ActivityStatus.COMPLETED);
        assertThat(activities.findById(activityId).orElseThrow().getRewardIssuedAt()).isNotNull();
        assertThat(records.countByActivityId(activityId)).isOne();
    }

    @Test
    void selfEmotionQuizCompletesEvenWhenEveryItemIsTechnicallyExcluded() throws Exception {
        UUID activityId = createActivity(ActivityType.SELF_EMOTION_QUIZ);
        UUID cameraUnreadable = assign(activityId, QuizType.SELF_EMOTION_SITUATION, 1);
        UUID cameraDenied = assign(activityId, QuizType.SELF_EMOTION_SITUATION, 2);

        // 세 번 찍어도 표정을 판정하지 못한 문항과 카메라 권한이 없어 풀지 못한 문항. 둘 다 정답률에서 빠진다.
        mvc.perform(
                        attempt(
                                cameraUnreadable,
                                "{\"expressionMatchResult\":\"NOT_ANALYZABLE\","
                                        + "\"cameraModelVersion\":\"camera-v1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.excludedFromScoring").value(true));
        expectStatus(activityId, childSession, ActivityStatus.IN_PROGRESS);
        mvc.perform(
                        attempt(
                                cameraDenied,
                                "{\"technicalFailureReason\":\"CAMERA_PERMISSION_DENIED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.excludedFromScoring").value(true));

        expectStatus(activityId, childSession, ActivityStatus.COMPLETED);
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validQuestionCount").value(0))
                .andExpect(jsonPath("$.data.overallAccuracy").doesNotExist());
        assertThat(activities.findById(activityId).orElseThrow().getRewardIssuedAt()).isNotNull();
        assertThat(records.countByActivityId(activityId)).isOne();
    }

    @Test
    void failedRecordSaveRollsBackTheWholeLastAnswerAndRetrySucceeds() throws Exception {
        UUID activityId = createActivity(ActivityType.OTHER_EMOTION_QUIZ);
        UUID only = assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 1);
        // 기록 저장을 실패시키려고 같은 활동의 기록을 먼저 넣어 둔다. 서버의 저장은 활동 ID 유일 제약에 걸린다.
        UUID planted = UUID.randomUUID();
        records.saveAndFlush(new LearningRecord(planted, activityId, Instant.now()));

        mvc.perform(attempt(only, "{\"firstResponse\":\"슬픔\"}"))
                .andExpect(status().isInternalServerError());

        // 응답 저장·퀴즈 결과·완료·스탬프가 모두 되돌아가 처음 상태다.
        Activity untouched = activities.findById(activityId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(ActivityStatus.NOT_STARTED);
        assertThat(untouched.getCompletedAt()).isNull();
        assertThat(untouched.getRewardIssuedAt()).isNull();
        assertThat(attempts.findByActivityQuizId(only)).isEmpty();
        assertThat(results.existsById(activityId)).isFalse();

        records.deleteById(planted);
        answer(only, "슬픔", null);

        expectStatus(activityId, childSession, ActivityStatus.COMPLETED);
        assertThat(results.existsById(activityId)).isTrue();
        assertThat(records.countByActivityId(activityId)).isOne();
        assertThat(records.existsById(planted)).isFalse();
    }

    @Test
    void firstCombinedQuizDoesNotCompleteTheActivity() throws Exception {
        UUID activityId = createActivity(ActivityType.FIRST_COMBINED);
        UUID only = assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 1);

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
        List<UUID> assigned =
                List.of(
                        assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 1),
                        assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 2));
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
        answer(assign(activityId, QuizType.OTHER_EMOTION_SITUATION, 1), "슬픔", null);
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

    /** 상대 감정 문항의 정답은 "슬픔", 자기 감정 문항의 정답은 "기쁨"이다. */
    private UUID assign(UUID activityId, QuizType type, int order) {
        UUID itemId = UUID.randomUUID();
        boolean self = type == QuizType.SELF_EMOTION_SITUATION;
        String correct = self ? "기쁨" : "슬픔";
        items.save(
                new QuizItem(
                        itemId,
                        type,
                        self ? Emotion.JOY : Emotion.SADNESS,
                        "어떤 감정일까요?",
                        null,
                        List.of("기쁨", "슬픔"),
                        correct,
                        List.of(correct),
                        List.of(
                                new QuizItem.HintSpec("OBSERVABLE_FACE_CUE", "얼굴을 살펴봐요."),
                                new QuizItem.HintSpec("CHOICE_REDUCTION", "둘 중 골라봐요.")),
                        1));
        UUID id = UUID.randomUUID();
        assignments.save(new ActivityQuiz(id, activityId, itemId, 1, order));
        return id;
    }
}
