package com.neuringo.neuringobe.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import com.neuringo.neuringobe.activity.domain.ActivityType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

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

        Map<String, Object> initialResult =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", firstActivity);

        // 뒤에 푼 퀴즈는 독립 퀴즈다. 첫 난이도는 최초 결합 활동의 퀴즈로만 정한다.
        UUID laterActivity = createIndependentQuiz();
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
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM quiz_result WHERE activity_id = ?", firstActivity))
                .isEqualTo(initialResult);
        assertThat(initialDifficultyCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "동시 제출의 기술 제외 여부: {0}")
    @ValueSource(booleans = {false, true})
    void concurrentLastAnswerCreatesOneAttemptAndOneResult(boolean technical, CapturedOutput output)
            throws Exception {
        UUID activityId = createActivity();
        UUID assignment =
                assign(
                        activityId,
                        saveItem(
                                technical
                                        ? QuizType.SELF_EMOTION_SITUATION
                                        : QuizType.OTHER_EMOTION_SITUATION,
                                "슬픔"),
                        1);
        String request =
                technical
                        ? "{\"expressionMatchResult\":\"NOT_ANALYZABLE\",\"cameraModelVersion\":\"camera-v1\"}"
                        : "{\"firstResponse\":\"슬픔\"}";

        List<MvcResult> responses =
                concurrentlyWithChildLocked(
                        List.of(
                                () -> submitAnswer(assignment, request),
                                () -> submitAnswer(assignment, request)));

        assertThat(responses.stream().map(result -> result.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(201, 200);
        String firstAttemptId =
                JsonPath.read(
                        responses.getFirst().getResponse().getContentAsString(),
                        "$.data.attemptId");
        String secondAttemptId =
                JsonPath.read(
                        responses.getLast().getResponse().getContentAsString(), "$.data.attemptId");
        assertThat(secondAttemptId).isEqualTo(firstAttemptId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM quiz_attempt WHERE activity_quiz_id = ?",
                                Integer.class,
                                assignment))
                .isEqualTo(1);
        assertThat(resultCount(activityId)).isEqualTo(1);
        assertThat(initialDifficultyCount()).isEqualTo(1);
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        assertThat(snapshot.get("valid_question_count")).isEqualTo(technical ? 0 : 1);
        assertThat(snapshot.get("correct_question_count")).isEqualTo(technical ? 0 : 1);
        assertThat(snapshot.get("scenario_level")).isEqualTo(technical ? "L1" : "L3");
        assertThat(snapshot.get("initial_support_level")).isEqualTo(technical ? "S1" : "S0");
        assertThat(snapshot.get("difficulty_fallback_applied")).isEqualTo(technical);
        assertThat(snapshot.get("policy_version")).isEqualTo("INITIAL_QUIZ_V2");
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel())
                .isEqualTo(technical ? "S1" : "S0");
        assertThat(
                        output.getAll()
                                .lines()
                                .filter(line -> line.contains("reason=NO_VALID_QUESTIONS"))
                                .count())
                .isEqualTo(technical ? 1 : 0);
    }

    @Test
    void independentQuizCompletedConcurrentlyDoesNotTakeInitialDifficulty() throws Exception {
        UUID firstActivity = createActivity();
        UUID firstAssignment =
                assign(firstActivity, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        UUID independentActivity = createIndependentQuiz();
        UUID independentAssignment =
                assign(independentActivity, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);

        List<MvcResult> responses =
                concurrentlyWithChildLocked(
                        List.of(
                                () -> submitAnswer(firstAssignment, "{\"firstResponse\":\"슬픔\"}"),
                                () ->
                                        submitAnswer(
                                                independentAssignment,
                                                "{\"firstResponse\":\"기쁨\",\"finalResponse\":\"기쁨\"}")));

        assertThat(responses.stream().map(result -> result.getResponse().getStatus()).toList())
                .containsOnly(201)
                .hasSize(2);
        assertThat(resultCount(firstActivity)).isEqualTo(1);
        assertThat(resultCount(independentActivity)).isEqualTo(1);
        assertThat(initialDifficultyCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT initial_difficulty_used FROM quiz_result WHERE activity_id = ?",
                                Boolean.class,
                                firstActivity))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT initial_difficulty_used FROM quiz_result WHERE activity_id = ?",
                                Boolean.class,
                                independentActivity))
                .isFalse();
        assertThat(activities.findById(firstActivity).orElseThrow().getInitialSupportLevel())
                .isEqualTo("S0");
        assertThat(activities.findById(independentActivity).orElseThrow().getInitialSupportLevel())
                .isNull();
    }

    @Test
    void independentQuizCompletedEarlierDoesNotConsumeInitialDifficulty() throws Exception {
        UUID independentActivity = createIndependentQuiz();
        UUID independentAssignment =
                assign(independentActivity, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        assertThat(
                        submitAnswer(independentAssignment, "{\"firstResponse\":\"슬픔\"}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
        assertThat(initialDifficultyCount()).isZero();

        UUID firstActivity = createActivity();
        UUID firstAssignment =
                assign(firstActivity, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        assertThat(
                        submitAnswer(
                                        firstAssignment,
                                        "{\"firstResponse\":\"기쁨\",\"finalResponse\":\"기쁨\"}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);

        assertThat(initialDifficultyCount()).isEqualTo(1);
        assertThat(activities.findById(firstActivity).orElseThrow().getInitialSupportLevel())
                .isEqualTo("S2");
        assertThat(activities.findById(independentActivity).orElseThrow().getInitialSupportLevel())
                .isNull();
    }

    @Test
    void calculationFailureRollsBackLastAnswerAndSameRequestRecovers(CapturedOutput output)
            throws Exception {
        UUID activityId = createActivity();
        UUID first = assign(activityId, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        UUID last = assign(activityId, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 2);
        String request = "{\"firstResponse\":\"슬픔\"}";
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", first)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isCreated());
        jdbc.update(
                "UPDATE quiz_attempt SET excluded_from_scoring = true WHERE activity_quiz_id = ?",
                first);

        MvcResult failed =
                mvc.perform(
                                put("/api/v1/activity-quiz-items/{id}/attempt", last)
                                        .session(childSession)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(request))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.error.code").value("QUIZ_RESULT_CALCULATION_FAILED"))
                        .andReturn();

        String traceId =
                JsonPath.read(failed.getResponse().getContentAsString(), "$.error.traceId");
        List<String> errorLogs =
                output.getAll()
                        .lines()
                        .filter(line -> line.contains("code=QUIZ_RESULT_CALCULATION_FAILED"))
                        .toList();
        assertThat(errorLogs).hasSize(1);
        assertThat(errorLogs.getFirst())
                .contains("attempts=2", "policyVersion=INITIAL_QUIZ_V2", "traceId=" + traceId)
                .doesNotContain("퀴즈 아동", "슬픔", "firstResponse", childId.toString());
        assertThat(resultCount(activityId)).isZero();
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel()).isNull();
        mvc.perform(get("/api/v1/activity-quiz-items/{id}/attempt", last).session(childSession))
                .andExpect(status().isNotFound());

        jdbc.update(
                "UPDATE quiz_attempt SET excluded_from_scoring = false WHERE activity_quiz_id = ?",
                first);
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", last)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isCreated());
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", last)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isOk());
        assertThat(resultCount(activityId)).isEqualTo(1);
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel())
                .isEqualTo("S0");
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validQuestionCount").value(2))
                .andExpect(jsonPath("$.data.correctQuestionCount").value(2))
                .andExpect(jsonPath("$.data.scenarioLevel").value("L3"))
                .andExpect(jsonPath("$.data.difficultyFallbackApplied").value(false))
                .andExpect(jsonPath("$.data.difficultyFallbackReason").doesNotExist())
                .andExpect(jsonPath("$.data.policyVersion").value("INITIAL_QUIZ_V2"));
    }

    @Test
    void fallbackLogWaitsForCommitAndReplayDoesNotRepeatIt(CapturedOutput output) throws Exception {
        UUID activityId = createActivity();
        UUID assignment = assign(activityId, saveItem(QuizType.SELF_EMOTION_SITUATION, "기쁨"), 1);
        String request =
                "{\"expressionMatchResult\":\"NOT_ANALYZABLE\",\"cameraModelVersion\":\"camera-v1\"}";
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        transaction -> {
                            try {
                                mvc.perform(
                                                put(
                                                                "/api/v1/activity-quiz-items/{id}/attempt",
                                                                assignment)
                                                        .session(childSession)
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(request))
                                        .andExpect(status().isCreated());
                            } catch (Exception exception) {
                                throw new AssertionError(exception);
                            }
                            assertThat(resultCount(activityId)).isEqualTo(1);
                            assertThat(output.getAll()).doesNotContain("reason=NO_VALID_QUESTIONS");
                            transaction.setRollbackOnly();
                        });

        assertThat(resultCount(activityId)).isZero();
        assertThat(output.getAll()).doesNotContain("reason=NO_VALID_QUESTIONS");
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", assignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isCreated());
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", assignment)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validQuestionCount").value(0))
                .andExpect(jsonPath("$.data.overallAccuracy").doesNotExist())
                .andExpect(jsonPath("$.data.scenarioLevel").value("L1"))
                .andExpect(jsonPath("$.data.initialSupportLevel").value("S1"))
                .andExpect(jsonPath("$.data.difficultyFallbackReason").value("NO_VALID_QUESTIONS"))
                .andExpect(jsonPath("$.data.policyVersion").value("INITIAL_QUIZ_V2"));
        assertThat(resultCount(activityId)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT difficulty_fallback_reason FROM quiz_result WHERE activity_id = ?",
                                String.class,
                                activityId))
                .isEqualTo("NO_VALID_QUESTIONS");
        assertThat(
                        output.getAll()
                                .lines()
                                .filter(line -> line.contains("reason=NO_VALID_QUESTIONS"))
                                .toList())
                .hasSize(1);
        jdbc.update(
                "UPDATE quiz_result SET difficulty_fallback_reason = null, policy_version = 'INITIAL_QUIZ_V1'"
                        + " WHERE activity_id = ?",
                activityId);
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.difficultyFallbackReason").value("LEGACY_UNSPECIFIED"))
                .andExpect(jsonPath("$.data.policyVersion").value("INITIAL_QUIZ_V1"));
    }

    private List<MvcResult> concurrentlyWithChildLocked(List<Callable<MvcResult>> requests)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (Callable<MvcResult> request : requests) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                    return request.call();
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            // 아동 행을 잡아 두 요청의 DB 잠금 대기를 확인한 뒤 해제한다.
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            transaction -> {
                                jdbc.queryForObject(
                                        "SELECT child_id FROM child WHERE child_id = ? FOR UPDATE",
                                        UUID.class,
                                        childId);
                                start.countDown();
                                await().atMost(10, TimeUnit.SECONDS)
                                        .until(
                                                () ->
                                                        jdbc.queryForObject(
                                                                "SELECT count(*) FROM pg_stat_activity"
                                                                        + " WHERE datname = current_database()"
                                                                        + " AND wait_event_type = 'Lock'"
                                                                        + " AND (query LIKE '%from activity %'"
                                                                        + " OR query LIKE '%from child %')",
                                                                Integer.class),
                                                waiting -> waiting == requests.size());
                            });
            List<MvcResult> responses = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                responses.add(future.get(20, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MvcResult submitAnswer(UUID assignmentId, String request) throws Exception {
        return mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andReturn();
    }

    private int initialDifficultyCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM quiz_result r JOIN activity a ON a.activity_id = r.activity_id"
                        + " WHERE a.child_id = ? AND r.initial_difficulty_used",
                Integer.class,
                childId);
    }

    private int resultCount(UUID activityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM quiz_result WHERE activity_id = ?",
                Integer.class,
                activityId);
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
                                ActivityType.FIRST_COMBINED,
                                goalId,
                                UUID.randomUUID(),
                                Instant.now()));
        return activity.getActivityId();
    }

    /** 목표 없는 독립 퀴즈 활동. 최초 결합은 아동당 한 번이라 두 번째 활동은 이걸로 만든다. */
    private UUID createIndependentQuiz() {
        return activities
                .save(
                        new Activity(
                                UUID.randomUUID(),
                                childId,
                                ActivityType.OTHER_EMOTION_QUIZ,
                                null,
                                UUID.randomUUID(),
                                Instant.now()))
                .getActivityId();
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
