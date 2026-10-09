package com.neuringo.neuringobe.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class QuizFlowIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private UserAccountRepository users;
    @Autowired private ChildRepository children;
    @Autowired private QuizItemRepository items;
    @Autowired private ActivityQuizRepository assignments;
    @Autowired private ActivityRepository activities;

    private UUID childId;
    private MockHttpSession childSession;

    @BeforeEach
    void setUp() throws Exception {
        UUID classroomId = UUID.randomUUID();
        childId = UUID.randomUUID();
        TestInstructors.save(users, "teacher-quiz");
        classrooms.save(
                new Classroom(
                        classroomId,
                        TestInstructors.id("teacher-quiz"),
                        "퀴즈반",
                        ClassroomStatus.ACTIVE));
        children.save(new Child(childId, classroomId, "퀴즈 아동", ChildStatus.ACTIVE));
        String codeBody =
                mvc.perform(
                                post("/api/v1/children/{childId}/access-codes", childId)
                                        .with(TestInstructors.instructor("teacher-quiz"))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String code = JsonPath.read(codeBody, "$.data.accessCode");
        MvcResult entry =
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accessCode\":\"" + code + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        childSession = (MockHttpSession) entry.getRequest().getSession(false);
    }

    @Test
    void savesHintAwareResultWithoutExposingAnswerAndExcludesCameraFailure() throws Exception {
        UUID activityId = createActivity();
        UUID wrongThenHint = saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔");
        UUID technical = saveItem(QuizType.SELF_EMOTION_SITUATION, "기쁨");
        UUID firstAssignment = assign(activityId, wrongThenHint, 1);
        UUID secondAssignment = assign(activityId, technical, 2);

        mvc.perform(get("/api/v1/children/{childId}/activities", childId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data[0].activityId").value(activityId.toString()));
        String questions =
                mvc.perform(
                                get("/api/v1/activities/{id}/quiz-items", activityId)
                                        .session(childSession))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].questionText").isString())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(questions).doesNotContain("correctAnswer", "acceptableAnswers", "hints");

        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUIZ_NOT_COMPLETED"));

        String firstRequest = "{\"firstResponse\":\"기쁨\"}";
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", firstAssignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(firstRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.firstResponseCorrect").value(false))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", firstAssignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstResponse\":\"슬픔\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FIRST_RESPONSE_IMMUTABLE"));

        UUID hintKey = UUID.randomUUID();
        for (int replay = 0; replay < 2; replay++) {
            mvc.perform(
                            post("/api/v1/activity-quiz-items/{id}/hints", firstAssignment)
                                    .session(childSession)
                                    .with(csrf())
                                    .header("Idempotency-Key", hintKey))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.hintOrder").value(1));
        }

        String finalRequest = "{\"firstResponse\":\"기쁨\",\"finalResponse\":\"슬픔\"}";
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", firstAssignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(finalRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolvedAfterHint").value(true))
                .andExpect(jsonPath("$.data.firstResponseCorrect").value(false));
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", firstAssignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(finalRequest))
                .andExpect(status().isOk());

        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", secondAssignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"firstResponse\":\"기쁨\","
                                                + "\"expressionMatchResult\":\"NOT_ANALYZABLE\","
                                                + "\"cameraModelVersion\":\"camera-v1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.excludedFromScoring").value(true));

        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validQuestionCount").value(1))
                .andExpect(jsonPath("$.data.correctQuestionCount").value(0))
                .andExpect(jsonPath("$.data.totalHintCount").value(1))
                .andExpect(jsonPath("$.data.resolvedAfterHintCount").value(1))
                .andExpect(jsonPath("$.data.scenarioLevel").value("L1"))
                .andExpect(jsonPath("$.data.initialSupportLevel").value("S2"))
                .andExpect(jsonPath("$.data.initialDifficultyUsed").value(true));
    }

    @Test
    void otherChildCannotReadActivityOrAttemptAndAllTechnicalFallsBack() throws Exception {
        UUID activityId = createActivity();
        UUID assignmentId = assign(activityId, saveItem(QuizType.SELF_EMOTION_SITUATION, "기쁨"), 1);
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expressionMatchResult\":\"NOT_ANALYZABLE\","
                                                + "\"cameraModelVersion\":\"camera-v1\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validQuestionCount").value(0))
                .andExpect(jsonPath("$.data.overallAccuracy").doesNotExist())
                .andExpect(jsonPath("$.data.initialSupportLevel").value("S1"))
                .andExpect(jsonPath("$.data.difficultyFallbackApplied").value(true));

        UUID otherChild = UUID.randomUUID();
        UUID otherClass = UUID.randomUUID();
        classrooms.save(
                new Classroom(
                        otherClass,
                        TestInstructors.id("teacher-quiz"),
                        "다른 반",
                        ClassroomStatus.ACTIVE));
        children.save(new Child(otherChild, otherClass, "다른 아동", ChildStatus.ACTIVE));
        String codeBody =
                mvc.perform(
                                post("/api/v1/children/{id}/access-codes", otherChild)
                                        .with(TestInstructors.instructor("teacher-quiz"))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String code = JsonPath.read(codeBody, "$.data.accessCode");
        MvcResult entry =
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accessCode\":\"" + code + "\"}"))
                        .andReturn();
        MockHttpSession otherSession = (MockHttpSession) entry.getRequest().getSession(false);
        mvc.perform(get("/api/v1/children/{id}/activities", childId).session(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .session(otherSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyInternalRoleCanFinalizeDraftThroughPatch() throws Exception {
        UUID activityId = createActivity();
        UUID assignmentId = assign(activityId, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstResponse\":\"기쁨\"}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        patch("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"finalResponse\":\"기쁨\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        patch("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .with(user("internal-service").roles("SYSTEM"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"finalResponse\":\"기쁨\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINALIZED"));
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
    }

    @Test
    void laterQuizDoesNotReplaceFirstDifficultyDecision() throws Exception {
        UUID firstActivity = createActivity();
        UUID firstAssignment =
                assign(firstActivity, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", firstAssignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstResponse\":\"슬픔\"}"))
                .andExpect(status().isCreated());

        UUID laterActivity = createActivity();
        UUID laterAssignment =
                assign(laterActivity, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", laterAssignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"firstResponse\":\"기쁨\"," + "\"finalResponse\":\"기쁨\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", laterActivity).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initialDifficultyUsed").value(false));
        mvc.perform(get("/api/v1/children/{id}/activities", childId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].initialSupportLevel").doesNotExist())
                .andExpect(jsonPath("$.data[1].initialSupportLevel").value("S0"));
    }

    private UUID createActivity() throws Exception {
        String goalBody =
                mvc.perform(
                                post("/api/v1/children/{childId}/learning-goals", childId)
                                        .with(TestInstructors.instructor("teacher-quiz"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"감정 표현하기\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID goalId = UUID.fromString(JsonPath.read(goalBody, "$.data.goalId"));
        // 배정 API 는 서버가 문항을 자동으로 붙인다. 이 테스트는 풀이·결과 흐름을 보려고 문항을 직접 고르므로, 빈 활동을 저장한 뒤
        // assign() 으로 문항을 저장소에 직접 붙인다.
        Activity activity =
                activities.save(
                        new Activity(
                                UUID.randomUUID(),
                                childId,
                                goalId,
                                UUID.randomUUID(),
                                Instant.now()));
        return activity.getActivityId();
    }

    private UUID saveItem(QuizType type, String correct) {
        UUID id = UUID.randomUUID();
        items.save(
                new QuizItem(
                        id,
                        type,
                        "슬픔".equals(correct) ? Emotion.SADNESS : Emotion.JOY,
                        "어떤 감정일까요?",
                        null,
                        List.of("기쁨", "슬픔"),
                        correct,
                        List.of(correct),
                        List.of(
                                new QuizItem.HintSpec("OBSERVABLE_FACE_CUE", "얼굴을 살펴봐요."),
                                new QuizItem.HintSpec("CHOICE_REDUCTION", "둘 중 골라봐요.")),
                        1));
        return id;
    }

    // 강사가 문항을 고르는 API 는 없다. 풀이 흐름만 보려고 문항을 저장소로 직접 붙인다.
    private UUID assign(UUID activityId, UUID itemId, int order) {
        UUID id = UUID.randomUUID();
        assignments.save(new ActivityQuiz(id, activityId, itemId, 1, order));
        return id;
    }
}
