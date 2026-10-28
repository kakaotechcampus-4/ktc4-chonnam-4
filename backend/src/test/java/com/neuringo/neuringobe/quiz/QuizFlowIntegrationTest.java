package com.neuringo.neuringobe.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import com.neuringo.neuringobe.quiz.service.FirstQuizCompleted;
import com.neuringo.neuringobe.quiz.service.InitialDifficultyQueryService;
import com.neuringo.neuringobe.roleplay.domain.RoleplaySession;
import com.neuringo.neuringobe.roleplay.dto.InitialRoleplayStartInput;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.roleplay.service.InitialRoleplayStartQueryService;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation;
import com.neuringo.neuringobe.scenario.service.InitialScenarioPreparationService;
import com.neuringo.neuringobe.scenario.service.ScenarioAttachmentService;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.domain.UserRole;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    QuizFlowIntegrationTest.CompletionEventConfiguration.class
})
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
    @Autowired private ObjectMapper mapper;
    @Autowired private InitialDifficultyQueryService initialDifficulties;
    @Autowired private InitialScenarioPreparationService scenarioPreparations;
    @Autowired private CompletionEvents completionEvents;
    @Autowired private ScenarioAttachmentService scenarioAttachments;
    @Autowired private InitialRoleplayStartQueryService roleplayStarts;
    @Autowired private RoleplaySessionRepository roleplaySessions;
    @Autowired private RoleplayRequestScopeService roleplayScopes;
    @Autowired private RoleplayStartService roleplayStartsOrRestores;

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
    void assignedQuizLinksDifficultyOnlyWhenCompleteAndKeepsSnapshotOnReplay() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "감정 표현하기"));
        List<ActivityQuiz> fixedQuestions =
                assignments.findByActivityIdOrderByQuestionOrder(activityId);
        assertThat(fixedQuestions).hasSize(3);
        List<QuizItem> fixedItems =
                fixedQuestions.stream()
                        .map(question -> items.findById(question.getItemId()).orElseThrow())
                        .toList();
        assertThat(fixedItems.stream().map(QuizItem::getQuizType).toList())
                .containsExactly(
                        QuizType.SELF_EMOTION_SITUATION,
                        QuizType.OTHER_EMOTION_SITUATION,
                        QuizType.OTHER_EMOTION_IMAGE);
        List<Map<String, Object>> questionSnapshot =
                jdbc.queryForList(
                        "SELECT * FROM activity_quiz WHERE activity_id = ? ORDER BY question_order",
                        activityId);

        assertThat(
                        submitAnswer(
                                        fixedQuestions.get(0).getActivityQuizId(),
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "firstResponse",
                                                        fixedItems.get(0).getCorrectAnswer(),
                                                        "expressionMatchResult",
                                                        "MATCH",
                                                        "cameraModelVersion",
                                                        "camera-test-v1")))
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);

        QuizItem secondItem = fixedItems.get(1);
        String firstWrong =
                secondItem.getChoices().stream()
                        .filter(answer -> !answer.equals(secondItem.getCorrectAnswer()))
                        .filter(answer -> !secondItem.getAcceptableAnswers().contains(answer))
                        .findFirst()
                        .orElseThrow();
        UUID secondAssignment = fixedQuestions.get(1).getActivityQuizId();
        assertThat(
                        submitAnswer(
                                        secondAssignment,
                                        mapper.writeValueAsString(
                                                Map.of("firstResponse", firstWrong)))
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
        mvc.perform(
                        post("/api/v1/activity-quiz-items/{id}/hints", secondAssignment)
                                .session(childSession)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isCreated());
        assertThat(
                        submitAnswer(
                                        secondAssignment,
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "firstResponse",
                                                        firstWrong,
                                                        "finalResponse",
                                                        secondItem.getCorrectAnswer())))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);

        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUIZ_NOT_COMPLETED"));
        assertThat(resultCount(activityId)).isZero();
        assertThat(initialDifficultyCount()).isZero();
        assertThat(completionEvents.forActivity(activityId)).isEmpty();
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel()).isNull();

        assertInitialDifficultyNotReady(activityId);
        assertScenarioPreparationNotReady(activityId, "INITIAL_DIFFICULTY_NOT_READY");
        UUID lastAssignment = fixedQuestions.get(2).getActivityQuizId();
        String lastRequest = "{\"technicalFailureReason\":\"IMAGE_LOAD_FAILED\"}";
        assertThat(submitAnswer(lastAssignment, lastRequest).getResponse().getStatus())
                .isEqualTo(201);
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validQuestionCount").value(2))
                .andExpect(jsonPath("$.data.correctQuestionCount").value(1))
                .andExpect(jsonPath("$.data.overallAccuracy").value(0.5))
                .andExpect(jsonPath("$.data.totalHintCount").value(1))
                .andExpect(jsonPath("$.data.resolvedAfterHintCount").value(1))
                .andExpect(jsonPath("$.data.scenarioLevel").value("L2"))
                .andExpect(jsonPath("$.data.initialSupportLevel").value("S1"))
                .andExpect(jsonPath("$.data.difficultyFallbackApplied").value(false))
                .andExpect(jsonPath("$.data.initialDifficultyUsed").value(true))
                .andExpect(jsonPath("$.data.policyVersion").value("INITIAL_QUIZ_V2"));
        Activity applied = activities.findById(activityId).orElseThrow();
        assertThat(completionEvents.forActivity(activityId))
                .containsExactly(
                        new FirstQuizCompleted(activityId, childId, "L2"),
                        new FirstQuizCompleted(activityId, childId, "L2"));
        assertThat(applied.getInitialSupportLevel()).isEqualTo("S1");
        assertThat(applied.isDifficultyFallbackApplied()).isFalse();
        InitialDifficultyEvidence evidence =
                initialDifficulties.requireForActivity(activityId, childAuthentication());
        assertThat(evidence)
                .isEqualTo(
                        new InitialDifficultyEvidence(
                                activityId,
                                childId,
                                applied.getGoalId(),
                                2,
                                1,
                                "L2",
                                "S1",
                                false,
                                null,
                                "INITIAL_QUIZ_V2"));
        Map<String, Object> resultSnapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        Map<String, Object> activitySnapshot =
                jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId);
        InitialScenarioPreparation preparation =
                scenarioPreparations.prepare(
                        activityId, instructorAuthentication(TestInstructors.id("teacher-quiz")));
        assertThat(preparation.initialDifficulty()).isEqualTo(evidence);
        assertThat(preparation.learningGoal().goalId()).isEqualTo(applied.getGoalId());
        assertThat(preparation.reuseKey().scenarioLevel()).isEqualTo("L2");
        assertThat(preparation.reuseKey().classId())
                .isEqualTo(children.findById(childId).orElseThrow().getClassId());

        assertThat(submitAnswer(lastAssignment, lastRequest).getResponse().getStatus())
                .isEqualTo(200);
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/activities/{id}/quiz-items", activityId).session(childSession))
                .andExpect(status().isOk());
        assertThat(initialDifficulties.requireForActivity(activityId, childAuthentication()))
                .isEqualTo(evidence);
        assertThat(
                        scenarioPreparations.prepare(
                                activityId,
                                instructorAuthentication(TestInstructors.id("teacher-quiz"))))
                .isEqualTo(preparation);
        assertThat(resultCount(activityId)).isEqualTo(1);
        assertThat(initialDifficultyCount()).isEqualTo(1);
        assertThat(completionEvents.forActivity(activityId))
                .containsExactly(
                        new FirstQuizCompleted(activityId, childId, "L2"),
                        new FirstQuizCompleted(activityId, childId, "L2"),
                        new FirstQuizCompleted(activityId, childId, "L2"));
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(resultSnapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId))
                .isEqualTo(activitySnapshot);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM activity_quiz WHERE activity_id = ? ORDER BY question_order",
                                activityId))
                .isEqualTo(questionSnapshot);
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
        InitialDifficultyEvidence initialEvidence =
                initialDifficulties.requireForActivity(firstActivity, childAuthentication());

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
        assertThat(completionEvents.forActivity(laterActivity)).isEmpty();
        assertThat(completionEvents.forActivity(firstActivity))
                .containsExactly(new FirstQuizCompleted(firstActivity, childId, "L3"));
        mvc.perform(get("/api/v1/children/{id}/activities", childId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].initialSupportLevel").doesNotExist())
                .andExpect(jsonPath("$.data[1].initialSupportLevel").value("S0"));
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM quiz_result WHERE activity_id = ?", firstActivity))
                .isEqualTo(initialResult);
        assertThat(initialDifficultyCount()).isEqualTo(1);
        assertThat(initialDifficulties.requireForActivity(firstActivity, childAuthentication()))
                .isEqualTo(initialEvidence);
        assertInitialDifficultyNotReady(laterActivity);
        assertScenarioPreparationNotReady(laterActivity, "INITIAL_DIFFICULTY_NOT_READY");
    }

    @Test
    void scenarioPreparationCarriesStoredGoalConditionsAndDifficultyWithoutWrites()
            throws Exception {
        Map<String, Object> goal =
                Map.of(
                        "title",
                        "친구의 기분을 생각해요",
                        "category",
                        "EMOTION_RECOGNITION",
                        "situationType",
                        "CLASSROOM",
                        "characters",
                        List.of(Map.of("id", "FRIEND", "role", "같은 반 친구")),
                        "requiredElements",
                        List.of("친구가 넘어진 상황"),
                        "forbiddenExpressions",
                        List.of("일부러 넘어졌다고 단정"));
        UUID activityId = assignFirstActivity(childId, goal);
        completeAssignedQuiz(activityId, true);
        Activity activity = activities.findById(activityId).orElseThrow();
        Map<String, Object> goalSnapshot =
                jdbc.queryForMap(
                        "SELECT * FROM learning_goal WHERE goal_id = ?", activity.getGoalId());
        Map<String, Object> resultSnapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        Map<String, Object> activitySnapshot =
                jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId);

        InitialScenarioPreparation prepared =
                scenarioPreparations.prepare(
                        activityId, instructorAuthentication(TestInstructors.id("teacher-quiz")));

        assertThat(prepared.learningGoal().goalId()).isEqualTo(activity.getGoalId());
        assertThat(prepared.learningGoal().childId()).isEqualTo(childId);
        assertThat(prepared.learningGoal().title()).isEqualTo(goal.get("title"));
        assertThat(prepared.learningGoal().category()).isEqualTo(goal.get("category"));
        assertThat(prepared.learningGoal().situationType()).isEqualTo(goal.get("situationType"));
        assertThat(prepared.learningGoal().characters()).isEqualTo(goal.get("characters"));
        assertThat(prepared.learningGoal().requiredElements())
                .isEqualTo(goal.get("requiredElements"));
        assertThat(prepared.learningGoal().forbiddenExpressions())
                .isEqualTo(goal.get("forbiddenExpressions"));
        assertThat(prepared.reuseKey())
                .isEqualTo(
                        new InitialScenarioPreparation.ReuseKey(
                                children.findById(childId).orElseThrow().getClassId(),
                                prepared.learningGoal().contentHash(),
                                "L3"));
        assertThat(prepared.initialDifficulty().activityId()).isEqualTo(activityId);
        assertThat(prepared.initialDifficulty().initialSupportLevel()).isEqualTo("S0");
        assertThat(prepared.initialDifficulty().policyVersion()).isEqualTo("INITIAL_QUIZ_V2");
        assertThat(mapper.writeValueAsString(prepared)).doesNotContain("퀴즈 아동");
        assertThat(
                        scenarioPreparations.prepare(
                                activityId,
                                instructorAuthentication(TestInstructors.id("teacher-quiz"))))
                .isEqualTo(prepared);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM learning_goal WHERE goal_id = ?",
                                activity.getGoalId()))
                .isEqualTo(goalSnapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(resultSnapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId))
                .isEqualTo(activitySnapshot);
    }

    @ParameterizedTest(name = "재사용 키 조건: {0}")
    @ValueSource(strings = {"SAME", "CLASS", "GOAL", "LEVEL"})
    void scenarioReuseKeyIncludesClassGoalHashAndLevel(String difference) throws Exception {
        Map<String, Object> firstGoal = Map.of("title", "친구 마음 알아보기");
        UUID firstActivity = assignFirstActivity(childId, firstGoal);
        completeAssignedQuiz(firstActivity, true);
        UUID classId = children.findById(childId).orElseThrow().getClassId();
        if (difference.equals("CLASS")) {
            classId = UUID.randomUUID();
            classrooms.save(
                    new Classroom(
                            classId,
                            TestInstructors.id("teacher-quiz"),
                            "다른 준비 학급",
                            ClassroomStatus.ACTIVE));
        }
        UUID secondChild = UUID.randomUUID();
        children.save(new Child(secondChild, classId, "준비 검증 아동", ChildStatus.ACTIVE));
        Map<String, Object> secondGoal =
                difference.equals("GOAL")
                        ? Map.of("title", "친구 마음 알아보기", "forbiddenExpressions", List.of("잘못된 단정"))
                        : firstGoal;
        UUID secondActivity = assignFirstActivity(secondChild, secondGoal);
        completeAssignedQuiz(secondActivity, !difference.equals("LEVEL"));
        Authentication teacher = instructorAuthentication(TestInstructors.id("teacher-quiz"));

        InitialScenarioPreparation first = scenarioPreparations.prepare(firstActivity, teacher);
        InitialScenarioPreparation second = scenarioPreparations.prepare(secondActivity, teacher);

        if (difference.equals("SAME")) {
            assertThat(second.reuseKey()).isEqualTo(first.reuseKey());
        } else {
            assertThat(second.reuseKey()).isNotEqualTo(first.reuseKey());
        }
        assertThat(second.initialDifficulty().activityId()).isNotEqualTo(firstActivity);
        assertThat(second.initialDifficulty().childId()).isEqualTo(secondChild);
    }

    @Test
    void scenarioPreparationRejectsMissingGoalHashWithoutRepairingIt() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "친구 마음 알아보기"));
        completeAssignedQuiz(activityId, true);
        UUID goalId = activities.findById(activityId).orElseThrow().getGoalId();
        jdbc.update("UPDATE learning_goal SET content_hash = ' ' WHERE goal_id = ?", goalId);

        assertScenarioPreparationNotReady(activityId, "INITIAL_SCENARIO_INPUT_NOT_READY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT content_hash FROM learning_goal WHERE goal_id = ?",
                                String.class,
                                goalId))
                .isEqualTo(" ");
        assertThat(resultCount(activityId)).isEqualTo(1);
    }

    @Test
    void initialEvidenceHidesMissingAndOtherOwnersActivities() throws Exception {
        UUID activityId = createActivity();
        UUID assignment = assign(activityId, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        assertThat(submitAnswer(assignment, "{\"firstResponse\":\"슬픔\"}").getResponse().getStatus())
                .isEqualTo(201);
        Authentication teacher = instructorAuthentication(TestInstructors.id("teacher-quiz"));
        assertThat(initialDifficulties.requireForActivity(activityId, teacher))
                .isEqualTo(
                        initialDifficulties.requireForActivity(activityId, childAuthentication()));

        Authentication otherChild =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(UUID.randomUUID()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        Authentication otherTeacher = instructorAuthentication(UUID.randomUUID());
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        for (Authentication caller : List.of(otherChild, otherTeacher)) {
            assertThatThrownBy(() -> initialDifficulties.requireForActivity(activityId, caller))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> {
                                assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                                assertThat(error.getCode()).isEqualTo("ACTIVITY_NOT_FOUND");
                            });
            assertThatThrownBy(() -> scenarioPreparations.prepare(activityId, caller))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> {
                                assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                                assertThat(error.getCode()).isEqualTo("ACTIVITY_NOT_FOUND");
                            });
        }
        assertThatThrownBy(() -> scenarioPreparations.prepare(activityId, childAuthentication()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                            assertThat(error.getCode()).isEqualTo("GOAL_NOT_FOUND");
                        });
        assertThatThrownBy(() -> initialDifficulties.requireForActivity(UUID.randomUUID(), teacher))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                            assertThat(error.getCode()).isEqualTo("ACTIVITY_NOT_FOUND");
                        });
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(snapshot);
    }

    @Test
    void independentQuizCannotBecomeInitialEvidenceEvenWithAnIncorrectUseFlag() throws Exception {
        UUID activityId = createIndependentQuiz();
        UUID assignment = assign(activityId, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        assertThat(submitAnswer(assignment, "{\"firstResponse\":\"슬픔\"}").getResponse().getStatus())
                .isEqualTo(201);
        jdbc.update(
                "UPDATE quiz_result SET initial_difficulty_used = true WHERE activity_id = ?",
                activityId);
        jdbc.update(
                "UPDATE activity SET initial_support_level = 'S0' WHERE activity_id = ?",
                activityId);

        assertInitialDifficultyNotReady(activityId);
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
        assertThat(completionEvents.forActivity(activityId)).isEmpty();
    }

    @ParameterizedTest(name = "최초 난이도 근거 불일치: {0}")
    @ValueSource(strings = {"SUPPORT", "FALLBACK", "UNUSED", "LEVEL", "POLICY", "COUNT"})
    void initialEvidenceRejectsInconsistentSnapshotsWithoutRepairingThem(String mismatch)
            throws Exception {
        UUID activityId = createActivity();
        UUID assignment = assign(activityId, saveItem(QuizType.OTHER_EMOTION_SITUATION, "슬픔"), 1);
        assertThat(submitAnswer(assignment, "{\"firstResponse\":\"슬픔\"}").getResponse().getStatus())
                .isEqualTo(201);
        switch (mismatch) {
            case "SUPPORT" ->
                    jdbc.update(
                            "UPDATE activity SET initial_support_level = 'S2' WHERE activity_id = ?",
                            activityId);
            case "FALLBACK" ->
                    jdbc.update(
                            "UPDATE activity SET difficulty_fallback_applied = true WHERE activity_id = ?",
                            activityId);
            case "UNUSED" ->
                    jdbc.update(
                            "UPDATE quiz_result SET initial_difficulty_used = false WHERE activity_id = ?",
                            activityId);
            case "LEVEL" ->
                    jdbc.update(
                            "UPDATE quiz_result SET scenario_level = null WHERE activity_id = ?",
                            activityId);
            case "POLICY" ->
                    jdbc.update(
                            "UPDATE quiz_result SET policy_version = ' ' WHERE activity_id = ?",
                            activityId);
            case "COUNT" ->
                    jdbc.update(
                            "UPDATE quiz_result SET correct_question_count = 2 WHERE activity_id = ?",
                            activityId);
            default -> throw new IllegalArgumentException("알 수 없는 검증 사례입니다.");
        }
        Map<String, Object> resultSnapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        Map<String, Object> activitySnapshot =
                jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId);

        assertInitialDifficultyNotReady(activityId);
        assertScenarioPreparationNotReady(activityId, "INITIAL_DIFFICULTY_NOT_READY");
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(resultSnapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId))
                .isEqualTo(activitySnapshot);
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
        assertThat(completionEvents.forActivity(activityId))
                .containsExactly(
                        new FirstQuizCompleted(activityId, childId, technical ? "L1" : "L3"));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void calculationFailureRollsBackLastAnswerAndSameRequestRecovers(
            boolean independent, CapturedOutput output) throws Exception {
        UUID activityId = independent ? createIndependentQuiz() : createActivity();
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
        assertThat(completionEvents.forActivity(activityId)).isEmpty();
        assertThat(resultCount(activityId)).isZero();
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel()).isNull();
        assertThat(activities.findById(activityId).orElseThrow().getStatus())
                .isEqualTo(ActivityStatus.IN_PROGRESS);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM learning_record WHERE activity_id = ?",
                                Long.class,
                                activityId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reward_issued_at FROM activity WHERE activity_id = ?",
                                Instant.class,
                                activityId))
                .isNull();
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
                .isEqualTo(independent ? null : "S0");
        assertThat(activities.findById(activityId).orElseThrow().getStatus())
                .isEqualTo(independent ? ActivityStatus.COMPLETED : ActivityStatus.IN_PROGRESS);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM learning_record WHERE activity_id = ?",
                                Long.class,
                                activityId))
                .isEqualTo(independent ? 1L : 0L);
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validQuestionCount").value(2))
                .andExpect(jsonPath("$.data.correctQuestionCount").value(2))
                .andExpect(jsonPath("$.data.scenarioLevel").value("L3"))
                .andExpect(jsonPath("$.data.difficultyFallbackApplied").value(false))
                .andExpect(jsonPath("$.data.difficultyFallbackReason").doesNotExist())
                .andExpect(jsonPath("$.data.policyVersion").value("INITIAL_QUIZ_V2"));
        if (independent) {
            assertThat(completionEvents.forActivity(activityId)).isEmpty();
        } else {
            assertThat(completionEvents.forActivity(activityId))
                    .containsExactly(
                            new FirstQuizCompleted(activityId, childId, "L3"),
                            new FirstQuizCompleted(activityId, childId, "L3"));
        }
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
                            assertThat(completionEvents.forActivity(activityId)).isEmpty();
                            assertThat(output.getAll()).doesNotContain("reason=NO_VALID_QUESTIONS");
                            transaction.setRollbackOnly();
                        });

        assertThat(resultCount(activityId)).isZero();
        assertThat(output.getAll()).doesNotContain("reason=NO_VALID_QUESTIONS");
        assertThat(completionEvents.forActivity(activityId)).isEmpty();
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
        assertThat(completionEvents.forActivity(activityId))
                .containsExactly(
                        new FirstQuizCompleted(activityId, childId, "L1"),
                        new FirstQuizCompleted(activityId, childId, "L1"));
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
        Map<String, Object> legacySnapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        InitialDifficultyEvidence legacy =
                initialDifficulties.requireForActivity(activityId, childAuthentication());
        assertThat(legacy.scenarioLevel()).isEqualTo("L1");
        assertThat(legacy.initialSupportLevel()).isEqualTo("S1");
        assertThat(legacy.validQuestionCount()).isZero();
        assertThat(legacy.difficultyFallbackApplied()).isTrue();
        assertThat(legacy.difficultyFallbackReason()).isEqualTo("LEGACY_UNSPECIFIED");
        assertThat(legacy.policyVersion()).isEqualTo("INITIAL_QUIZ_V1");
        InitialScenarioPreparation prepared =
                scenarioPreparations.prepare(
                        activityId, instructorAuthentication(TestInstructors.id("teacher-quiz")));
        assertThat(prepared.initialDifficulty()).isEqualTo(legacy);
        assertThat(prepared.reuseKey().scenarioLevel()).isEqualTo("L1");
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L1");
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarioLevel").value("L1"))
                .andExpect(jsonPath("$.data.initialSupportLevel").value("S1"))
                .andExpect(jsonPath("$.data.difficultyFallbackReason").value("LEGACY_UNSPECIFIED"))
                .andExpect(jsonPath("$.data.policyVersion").value("INITIAL_QUIZ_V1"));
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId())
                .isEqualTo(scenarioId);
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel())
                .isEqualTo("S1");
        var startInput = roleplayStarts.requireForActivity(activityId, childAuthentication());
        assertThat(startInput.initialDifficulty()).isEqualTo(legacy);
        assertThat(startInput.scenario().scenarioId()).isEqualTo(scenarioId);
        var started =
                roleplayStartsOrRestores.start(
                        activityId, UUID.randomUUID(), childAuthentication());
        RoleplaySession savedSession =
                roleplaySessions.findById(started.session().sessionId()).orElseThrow();
        assertThat(started.created()).isTrue();
        assertThat(
                        roleplayScopes
                                .loadForChild(
                                        childAuthentication(),
                                        activityId,
                                        savedSession.getSessionId())
                                .currentSupportLevel())
                .isEqualTo("S1");
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(legacySnapshot);
    }

    @Test
    void committedFirstQuizAttachesMatchingApprovedVersionAndReplayKeepsIt() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "승인본 연결"));
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        completeAssignedQuiz(activityId, true);

        assertThat(activities.findById(activityId).orElseThrow().getScenarioId())
                .isEqualTo(scenarioId);
        Map<String, Object> resultSnapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        Map<String, Object> activitySnapshot =
                jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId);
        UUID laterCandidate = saveScenarioForActivity(activityId, "APPROVED", "L3");
        scenarioAttachments.onFirstQuizCompleted(new FirstQuizCompleted(activityId, childId, "L3"));
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId))
                .isEqualTo(activitySnapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(resultSnapshot);
        assertThat(laterCandidate).isNotEqualTo(scenarioId);
    }

    @ParameterizedTest(name = "승인 후보 제외 조건: {0}")
    @ValueSource(strings = {"CLASS", "GOAL", "LEVEL", "DRAFT", "RETIRED", "UNSCOPED", "USED"})
    void firstQuizDoesNotAttachIneligibleScenario(String mismatch) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "조건에 맞는 승인본만"));
        String status =
                mismatch.equals("DRAFT") || mismatch.equals("RETIRED") ? mismatch : "APPROVED";
        // 승인 전 초안에서만 조건을 수정한다.
        UUID scenarioId =
                saveScenarioForActivity(
                        activityId, "DRAFT", mismatch.equals("LEVEL") ? "L1" : "L3");
        if (mismatch.equals("CLASS")) {
            UUID otherClass = UUID.randomUUID();
            classrooms.save(
                    new Classroom(
                            otherClass,
                            TestInstructors.id("teacher-quiz"),
                            "다른 반",
                            ClassroomStatus.ACTIVE));
            jdbc.update(
                    "UPDATE scenario SET class_id = ? WHERE scenario_id = ?",
                    otherClass,
                    scenarioId);
        } else if (mismatch.equals("GOAL")) {
            jdbc.update(
                    "UPDATE scenario SET goal_content_hash = ? WHERE scenario_id = ?",
                    "0".repeat(64),
                    scenarioId);
        } else if (mismatch.equals("UNSCOPED")) {
            jdbc.update(
                    "UPDATE scenario SET class_id = null, goal_content_hash = null WHERE scenario_id = ?",
                    scenarioId);
        } else if (mismatch.equals("USED")) {
            Activity previous =
                    new Activity(
                            UUID.randomUUID(),
                            childId,
                            ActivityType.ROLEPLAY,
                            activities.findById(activityId).orElseThrow().getGoalId(),
                            UUID.randomUUID(),
                            Instant.now());
            activities.saveAndFlush(previous);
            jdbc.update(
                    "UPDATE activity SET scenario_id = ?, status = 'COMPLETED' WHERE activity_id = ?",
                    scenarioId,
                    previous.getActivityId());
        }
        jdbc.update("UPDATE scenario SET status = ? WHERE scenario_id = ?", status, scenarioId);
        completeAssignedQuiz(activityId, true);
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
        assertThat(resultCount(activityId)).isEqualTo(1);
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel())
                .isEqualTo("S0");
    }

    @Test
    void approvedScenarioCannotChangeConditionsContentVersionOrBecomeDraft() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "승인 버전 불변"));
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        for (String change :
                List.of(
                        "title = '변경'",
                        "scenario_level = 'L1'",
                        "goal_content_hash = null",
                        "class_id = null",
                        "scenario_version = 2",
                        "status = 'DRAFT'")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE scenario SET "
                                                    + change
                                                    + " WHERE scenario_id = ?",
                                            scenarioId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        jdbc.update("UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?", scenarioId);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ?",
                                        scenarioId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void approvedMicroGoalsCannotBeAddedChangedOrDeleted() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "작은 목표도 승인 내용"));
        UUID scenarioId = saveScenarioForActivity(activityId, "DRAFT", "L3");
        UUID microGoalId = UUID.randomUUID();
        String insert =
                "INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence) VALUES (?, ?, ?, '감정 말하기', '[]'::jsonb)";
        jdbc.update(insert, microGoalId, scenarioId, 1);
        jdbc.update("UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ?", scenarioId);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), scenarioId, 2))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE scenario_micro_goal SET description = '변경' WHERE micro_goal_id = ?",
                                        microGoalId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM scenario_micro_goal WHERE micro_goal_id = ?",
                                        microGoalId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rollbackDoesNotAttachAndSuccessfulRetryUsesSameApprovedCandidate() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "저장 성공 후 연결"));
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            try {
                                completeAssignedQuiz(activityId, true);
                            } catch (Exception exception) {
                                throw new AssertionError(exception);
                            }
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT scenario_id FROM activity WHERE activity_id = ?",
                                                    UUID.class,
                                                    activityId))
                                    .isNull();
                            status.setRollbackOnly();
                        });
        assertThat(resultCount(activityId)).isZero();
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
        completeAssignedQuiz(activityId, true);
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId())
                .isEqualTo(scenarioId);
    }

    @Test
    void attachmentWriteFailureKeepsCommittedQuizAndResultReadRetriesWithoutRecalculation(
            CapturedOutput output) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "연결 저장 실패 복구"));
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        jdbc.execute(
                """
                CREATE FUNCTION test_reject_scenario_attachment() RETURNS trigger AS $$
                BEGIN
                    IF NEW.activity_id = '%s'::uuid AND NEW.scenario_id IS NOT NULL THEN
                        RAISE EXCEPTION 'Synthetic attachment failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER test_attachment_failure BEFORE UPDATE ON activity
                    FOR EACH ROW EXECUTE FUNCTION test_reject_scenario_attachment();
                """
                        .formatted(activityId));
        try {
            completeAssignedQuiz(activityId, true);
            assertThat(resultCount(activityId)).isEqualTo(1);
            assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel())
                    .isEqualTo("S0");
            assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
            mvc.perform(
                            get("/api/v1/activities/{id}/quiz-result", activityId)
                                    .session(childSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.initialSupportLevel").value("S0"));
            assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
            assertThat(output.getAll()).contains("시나리오 연결 실패");
        } finally {
            jdbc.execute(
                    "DROP TRIGGER test_attachment_failure ON activity; DROP FUNCTION test_reject_scenario_attachment()");
        }
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId())
                .isEqualTo(scenarioId);
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(snapshot);
    }

    @Test
    void resultReadConnectsNewlyAvailableApprovedScenarioAndKeepsResultResponse() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "나중에 준비된 승인본"));
        completeAssignedQuiz(activityId, true);
        MvcResult before =
                mvc.perform(
                                get("/api/v1/activities/{id}/quiz-result", activityId)
                                        .session(childSession))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);

        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        MvcResult recovered =
                mvc.perform(
                                get("/api/v1/activities/{id}/quiz-result", activityId)
                                        .session(childSession))
                        .andExpect(status().isOk())
                        .andReturn();
        Object beforeData = JsonPath.read(before.getResponse().getContentAsString(), "$.data");
        Object recoveredData =
                JsonPath.read(recovered.getResponse().getContentAsString(), "$.data");
        assertThat(recoveredData).isEqualTo(beforeData);
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId())
                .isEqualTo(scenarioId);
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(snapshot);

        int delivered = completionEvents.forActivity(activityId).size();
        Map<String, Object> applied =
                jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId);
        saveScenarioForActivity(activityId, "APPROVED", "L3");
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
        assertThat(completionEvents.forActivity(activityId)).hasSize(delivered);
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId))
                .isEqualTo(applied);
    }

    @ParameterizedTest(name = "결과 재조회 복구 제외: {0}")
    @ValueSource(strings = {"UNUSED", "COMPLETED"})
    void resultReadDoesNotRequestAttachmentForUnusedOrCompletedActivity(String excluded)
            throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "복구 제외 활동"));
        completeAssignedQuiz(activityId, true);
        saveScenarioForActivity(activityId, "APPROVED", "L3");
        if (excluded.equals("UNUSED")) {
            jdbc.update(
                    "UPDATE quiz_result SET initial_difficulty_used = false WHERE activity_id = ?",
                    activityId);
        } else {
            jdbc.update(
                    "UPDATE activity SET status = 'COMPLETED' WHERE activity_id = ?", activityId);
        }
        int delivered = completionEvents.forActivity(activityId).size();
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
        assertThat(completionEvents.forActivity(activityId)).hasSize(delivered);
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
    }

    @Test
    void unauthorizedResultReadCannotTriggerAttachmentButOwningInstructorCan() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "결과 복구 권한"));
        completeAssignedQuiz(activityId, true);
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        int delivered = completionEvents.forActivity(activityId).size();
        Authentication otherChild =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(UUID.randomUUID()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        for (Authentication caller :
                List.of(otherChild, instructorAuthentication(UUID.randomUUID()))) {
            mvc.perform(
                            get("/api/v1/activities/{id}/quiz-result", activityId)
                                    .with(authentication(caller)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        }
        assertThat(completionEvents.forActivity(activityId)).hasSize(delivered);
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
        mvc.perform(
                        get("/api/v1/activities/{id}/quiz-result", activityId)
                                .with(
                                        authentication(
                                                instructorAuthentication(
                                                        TestInstructors.id("teacher-quiz")))))
                .andExpect(status().isOk());
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId())
                .isEqualTo(scenarioId);
    }

    @Test
    void concurrentResultReadsAttachOnceAndPreserveTheOriginalDifficulty() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "동시 결과 복구"));
        completeAssignedQuiz(activityId, true);
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        saveScenarioForActivity(activityId, "APPROVED", "L3");
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        Callable<MvcResult> request =
                () ->
                        mvc.perform(
                                        get("/api/v1/activities/{id}/quiz-result", activityId)
                                                .session(childSession))
                                .andReturn();
        List<MvcResult> responses = concurrentlyWithChildLocked(List.of(request, request));
        assertThat(responses.stream().map(response -> response.getResponse().getStatus()).toList())
                .containsOnly(200);
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId())
                .isEqualTo(scenarioId);
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(snapshot);
        assertThat(resultCount(activityId)).isEqualTo(1);
    }

    @ParameterizedTest(name = "최초 시작 입력: 첫 정답 {0}개 → {1}/{2}")
    @CsvSource({"0,L1,S2", "2,L2,S1", "3,L3,S0"})
    void initialRoleplayInputPreservesAppliedSupportAndApprovedVersion(
            int correctCount, String level, String support) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "최초 지원 전달"));
        UUID scenarioId = saveScenarioForActivity(activityId, "DRAFT", level);
        jdbc.update(
                "UPDATE scenario SET scenario_version = 7, status = 'APPROVED' WHERE scenario_id = ?",
                scenarioId);
        completeAssignedQuiz(activityId, correctCount);
        Map<String, Object> activitySnapshot =
                jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId);
        Map<String, Object> resultSnapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        var input = roleplayStarts.requireForActivity(activityId, childAuthentication());
        assertThat(input.initialDifficulty().activityId()).isEqualTo(activityId);
        assertThat(input.initialDifficulty().childId()).isEqualTo(childId);
        assertThat(input.initialDifficulty().scenarioLevel()).isEqualTo(level);
        assertThat(input.initialDifficulty().initialSupportLevel()).isEqualTo(support);
        assertThat(input.initialDifficulty().policyVersion()).isEqualTo("INITIAL_QUIZ_V2");
        assertThat(input.scenario().scenarioId()).isEqualTo(scenarioId);
        assertThat(input.scenario().version()).isEqualTo(7);
        assertThat(roleplayStarts.requireForActivity(activityId, childAuthentication()))
                .isEqualTo(input);
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId))
                .isEqualTo(activitySnapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(resultSnapshot);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isZero();
        var started =
                roleplayStartsOrRestores.start(
                        activityId, UUID.randomUUID(), childAuthentication());
        RoleplaySession savedSession =
                roleplaySessions.findById(started.session().sessionId()).orElseThrow();
        assertThat(started.created()).isTrue();
        var scope =
                roleplayScopes.loadForChild(
                        childAuthentication(), activityId, savedSession.getSessionId());
        assertThat(scope.currentSupportLevel()).isEqualTo(support);
        assertThat(scope.scenarioVersion()).isEqualTo(7);
        assertThat(scope.lastTurnNumber()).isZero();
        assertThat(scope.checkpointVersion()).isZero();
        assertThat(scope.acceptsNewTurn()).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(resultSnapshot);
    }

    @ParameterizedTest(name = "최초 시작 차단: {0}")
    @ValueSource(strings = {"UNLINKED", "DRAFT", "RETIRED", "CLASS", "GOAL", "LEVEL", "COMPLETED"})
    void initialRoleplayInputRejectsUnreadyOrMismatchedApproval(String mismatch) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "시작 근거 확인"));
        completeAssignedQuiz(activityId, true);
        UUID scenarioId =
                saveScenarioForActivity(
                        activityId, "DRAFT", mismatch.equals("LEVEL") ? "L1" : "L3");
        if (mismatch.equals("CLASS")) {
            UUID otherClass = UUID.randomUUID();
            classrooms.save(
                    new Classroom(
                            otherClass,
                            TestInstructors.id("teacher-quiz"),
                            "다른 시작 학급",
                            ClassroomStatus.ACTIVE));
            jdbc.update(
                    "UPDATE scenario SET class_id = ? WHERE scenario_id = ?",
                    otherClass,
                    scenarioId);
        }
        if (mismatch.equals("GOAL")) {
            jdbc.update(
                    "UPDATE scenario SET goal_content_hash = ? WHERE scenario_id = ?",
                    "0".repeat(64),
                    scenarioId);
        }
        String status =
                mismatch.equals("DRAFT") || mismatch.equals("RETIRED") ? mismatch : "APPROVED";
        jdbc.update("UPDATE scenario SET status = ? WHERE scenario_id = ?", status, scenarioId);
        if (!mismatch.equals("UNLINKED")) {
            jdbc.update(
                    "UPDATE activity SET scenario_id = ? WHERE activity_id = ?",
                    scenarioId,
                    activityId);
        }
        if (mismatch.equals("COMPLETED")) {
            jdbc.update(
                    "UPDATE activity SET status = 'COMPLETED' WHERE activity_id = ?", activityId);
        }
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId);
        assertThatThrownBy(
                        () -> roleplayStarts.requireForActivity(activityId, childAuthentication()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.getCode()).isEqualTo("INITIAL_ROLEPLAY_NOT_READY");
                        });
        assertThat(jdbc.queryForMap("SELECT * FROM activity WHERE activity_id = ?", activityId))
                .isEqualTo(snapshot);
    }

    @Test
    void initialRoleplayInputRequiresTheOwningChild() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "본인만 시작"));
        saveScenarioForActivity(activityId, "APPROVED", "L3");
        completeAssignedQuiz(activityId, true);
        assertThatThrownBy(() -> roleplayStarts.requireForActivity(activityId, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(
                        () ->
                                roleplayStarts.requireForActivity(
                                        activityId,
                                        instructorAuthentication(
                                                TestInstructors.id("teacher-quiz"))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        Authentication otherChild =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(UUID.randomUUID()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        assertThatThrownBy(() -> roleplayStarts.requireForActivity(activityId, otherChild))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ACTIVITY_NOT_FOUND"));
    }

    @Test
    void initialRoleplayInputCannotResetAnExistingSessionsCurrentSupport() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "기존 세션은 초기화 금지"));
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        completeAssignedQuiz(activityId, true);
        UUID sessionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO roleplay_session (session_id, activity_id, child_id, scenario_id, scenario_version,
                    status, current_support_level, last_activity_at)
                VALUES (?, ?, ?, ?, 1, 'IN_PROGRESS', 'S3', ?)
                """,
                sessionId,
                activityId,
                childId,
                scenarioId,
                java.sql.Timestamp.from(Instant.now()));
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM roleplay_session WHERE session_id = ?", sessionId);
        assertThatThrownBy(
                        () -> roleplayStarts.requireForActivity(activityId, childAuthentication()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getCode())
                                        .isEqualTo("INITIAL_ROLEPLAY_NOT_READY"));
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM roleplay_session WHERE session_id = ?", sessionId))
                .isEqualTo(snapshot);
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel())
                .isEqualTo("S0");
    }

    @Test
    void roleplayStartCreatesOnceAndBothRequestKeysRestoreCurrentProgress() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID firstKey = UUID.randomUUID();
        var created = roleplayStartsOrRestores.start(activityId, firstKey, childAuthentication());
        assertThat(created.created()).isTrue();
        assertThat(created.session().currentSupportLevel()).isEqualTo("S0");
        assertThat(created.session().lastTurnNumber()).isZero();
        UUID sessionId = created.session().sessionId();
        UUID lastTurnId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            jdbc.update(
                                    """
                    INSERT INTO roleplay_turn (turn_id, session_id, request_id, idempotency_key, input_fingerprint,
                        turn_number, candidate_id, micro_goal_id, support_level, response_text, checkpoint_version, committed_at)
                    VALUES (?, ?, ?, ?, ?, 1, ?, ?, 'S2', '합성 확정 응답', 1, ?)
                    """,
                                    lastTurnId,
                                    sessionId,
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    "a".repeat(64),
                                    UUID.randomUUID(),
                                    goalId,
                                    java.sql.Timestamp.from(Instant.now()));
                            jdbc.update(
                                    """
                    UPDATE roleplay_session SET last_turn_id = ?, last_turn_number = 1,
                        current_micro_goal_id = ?, current_support_level = 'S2', row_version = 1
                    WHERE session_id = ?
                    """,
                                    lastTurnId,
                                    goalId,
                                    sessionId);
                        });
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM roleplay_session WHERE session_id = ?", sessionId);
        UUID newKey = UUID.randomUUID();
        for (UUID key : List.of(firstKey, newKey, newKey)) {
            var restored = roleplayStartsOrRestores.start(activityId, key, childAuthentication());
            assertThat(restored.created()).isFalse();
            assertThat(restored.session().sessionId()).isEqualTo(sessionId);
            assertThat(restored.session().currentSupportLevel()).isEqualTo("S2");
            assertThat(restored.session().currentMicroGoalId()).isEqualTo(goalId);
            assertThat(restored.session().lastTurnId()).isEqualTo(lastTurnId);
            assertThat(restored.session().lastTurnNumber()).isEqualTo(1);
            assertThat(restored.session().checkpointVersion()).isEqualTo(1);
        }
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM roleplay_session WHERE session_id = ?", sessionId))
                .isEqualTo(snapshot);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_request WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(2);
        assertThat(activities.findById(activityId).orElseThrow().getInitialSupportLevel())
                .isEqualTo("S0");
    }

    @ParameterizedTest(name = "기존 세션 상태 유지: {0}")
    @ValueSource(strings = {"PAUSED", "COMPLETED"})
    void roleplayStartRestoresPausedAndCompletedWithoutRestarting(String state) throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        var created =
                roleplayStartsOrRestores.start(
                        activityId, UUID.randomUUID(), childAuthentication());
        UUID sessionId = created.session().sessionId();
        jdbc.update(
                "UPDATE roleplay_session SET status = ? WHERE session_id = ?", state, sessionId);
        jdbc.update("UPDATE activity SET status = ? WHERE activity_id = ?", state, activityId);
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM roleplay_session WHERE session_id = ?", sessionId);
        var restored =
                roleplayStartsOrRestores.start(
                        activityId, UUID.randomUUID(), childAuthentication());
        assertThat(restored.created()).isFalse();
        assertThat(restored.session().status()).isEqualTo(state);
        assertThat(restored.session().activityStatus().name()).isEqualTo(state);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM roleplay_session WHERE session_id = ?", sessionId))
                .isEqualTo(snapshot);
    }

    @ParameterizedTest(name = "동시 시작 요청의 동일 키 여부: {0}")
    @ValueSource(booleans = {true, false})
    void concurrentRoleplayStartsCreateOneSession(boolean sameKey) throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID key = UUID.randomUUID();
        UUID secondKey = sameKey ? key : UUID.randomUUID();
        Authentication owner = childAuthentication();
        Callable<RoleplayStartService.StartResult> first =
                () -> roleplayStartsOrRestores.start(activityId, key, owner);
        Callable<RoleplayStartService.StartResult> second =
                () -> roleplayStartsOrRestores.start(activityId, secondKey, owner);
        var responses = concurrentlyWithChildLocked(List.of(first, second));
        assertThat(responses.stream().filter(RoleplayStartService.StartResult::created)).hasSize(1);
        assertThat(responses.getFirst().session().sessionId())
                .isEqualTo(responses.getLast().session().sessionId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_request WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(sameKey ? 1 : 2);
    }

    @Test
    void roleplayRequestKeyCannotBeReusedForAnotherChildsActivity() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID key = UUID.randomUUID();
        roleplayStartsOrRestores.start(activityId, key, childAuthentication());
        UUID otherChild = UUID.randomUUID();
        children.save(
                new Child(
                        otherChild,
                        children.findById(childId).orElseThrow().getClassId(),
                        "다른 합성 아동",
                        ChildStatus.ACTIVE));
        UUID otherActivity = readyRoleplayActivity(otherChild);
        Authentication owner =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(otherChild),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        assertThatThrownBy(() -> roleplayStartsOrRestores.start(otherActivity, key, owner))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                otherActivity))
                .isZero();
    }

    @Test
    void concurrentDifferentActivitiesCannotStealTheSameStartRequestKey() throws Exception {
        UUID firstActivity = readyRoleplayActivity(childId);
        UUID otherChild = UUID.randomUUID();
        children.save(
                new Child(
                        otherChild,
                        children.findById(childId).orElseThrow().getClassId(),
                        "키 경합 합성 아동",
                        ChildStatus.ACTIVE));
        UUID secondActivity = readyRoleplayActivity(otherChild);
        UUID key = UUID.randomUUID();
        Authentication firstOwner = childAuthentication();
        Authentication secondOwner =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(otherChild),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        Callable<Object> first =
                () -> {
                    try {
                        return roleplayStartsOrRestores.start(firstActivity, key, firstOwner);
                    } catch (ApiException exception) {
                        return exception;
                    }
                };
        Callable<Object> second =
                () -> {
                    try {
                        return roleplayStartsOrRestores.start(secondActivity, key, secondOwner);
                    } catch (ApiException exception) {
                        return exception;
                    }
                };
        var results = concurrentlyWithDatabaseLock(List.of(first, second), true);
        assertThat(results.stream().filter(RoleplayStartService.StartResult.class::isInstance))
                .hasSize(1);
        assertThat(
                        results.stream()
                                .filter(ApiException.class::isInstance)
                                .map(ApiException.class::cast)
                                .map(ApiException::getCode))
                .containsExactly("IDEMPOTENCY_KEY_REUSED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id IN (?, ?)",
                                Integer.class,
                                firstActivity,
                                secondActivity))
                .isEqualTo(1);
        var winner =
                results.stream()
                        .filter(RoleplayStartService.StartResult.class::isInstance)
                        .map(RoleplayStartService.StartResult.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT session_id FROM roleplay_start_request WHERE request_key = ?",
                                UUID.class,
                                key))
                .isEqualTo(winner.session().sessionId());
    }

    @Test
    void roleplayStartFailureRollsBackSessionAndRequestAndSameKeyRecovers() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID key = UUID.randomUUID();
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId);
        jdbc.execute(
                """
                CREATE FUNCTION test_reject_roleplay_start() RETURNS trigger AS $$
                BEGIN
                    IF NEW.request_key = '%s'::uuid THEN
                        RAISE EXCEPTION 'Synthetic start failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER test_start_failure BEFORE INSERT ON roleplay_start_request
                    FOR EACH ROW EXECUTE FUNCTION test_reject_roleplay_start();
                """
                        .formatted(key));
        try {
            assertThatThrownBy(
                            () ->
                                    roleplayStartsOrRestores.start(
                                            activityId, key, childAuthentication()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                    Integer.class,
                                    activityId))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM roleplay_start_request WHERE request_key = ?",
                                    Integer.class,
                                    key))
                    .isZero();
        } finally {
            jdbc.execute(
                    "DROP TRIGGER test_start_failure ON roleplay_start_request; DROP FUNCTION test_reject_roleplay_start()");
        }
        assertThat(roleplayStartsOrRestores.start(activityId, key, childAuthentication()).created())
                .isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM quiz_result WHERE activity_id = ?", activityId))
                .isEqualTo(snapshot);
    }

    @Test
    void roleplayStartRejectsMissingApprovalAndCannotStartAnotherChildsActivity() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "준비 전 시작 금지"));
        completeAssignedQuiz(activityId, true);
        UUID key = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                roleplayStartsOrRestores.start(
                                        activityId, key, childAuthentication()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getCode())
                                        .isEqualTo("INITIAL_ROLEPLAY_NOT_READY"));
        Authentication other =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(UUID.randomUUID()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        assertThatThrownBy(() -> roleplayStartsOrRestores.start(activityId, key, other))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ACTIVITY_NOT_FOUND"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_request WHERE request_key = ?",
                                Integer.class,
                                key))
                .isZero();
    }

    @Test
    void roleplayStartDoesNotChooseBetweenAmbiguousLegacySessions() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        var input = roleplayStarts.requireForActivity(activityId, childAuthentication());
        saveInitialSession(input);
        saveInitialSession(input);
        assertThatThrownBy(
                        () ->
                                roleplayStartsOrRestores.start(
                                        activityId, UUID.randomUUID(), childAuthentication()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getCode())
                                        .isEqualTo("ROLEPLAY_SESSION_AMBIGUOUS"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(2);
    }

    @Test
    void httpRoleplayStartCreatesAndRestoresTheSameApprovedOpening() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID key = UUID.randomUUID();
        MvcResult first = postRoleplayStart(activityId, key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        Object initialData = JsonPath.read(first.getResponse().getContentAsString(), "$.data");
        String sessionId =
                JsonPath.read(first.getResponse().getContentAsString(), "$.data.sessionId");
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .session(childSession)
                                .with(csrf())
                                .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery.messages[0].purpose").value("QUESTION"))
                .andExpect(jsonPath("$.data.delivery.messages[0].text").value("친구는 어떤 기분일까?"))
                .andExpect(jsonPath("$.data.delivery.messages[0].speech.available").value(false))
                .andExpect(jsonPath("$.data.delivery.messages[0].speech.url").doesNotExist())
                .andExpect(jsonPath("$.data.currentSupportLevel").doesNotExist())
                .andExpect(jsonPath("$.data.initialDifficulty").doesNotExist());
        MvcResult restored = postRoleplayStart(activityId, UUID.randomUUID());
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        Object restoredData = JsonPath.read(restored.getResponse().getContentAsString(), "$.data");
        assertThat(restoredData).isEqualTo(initialData);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_message WHERE session_id = ?",
                                Integer.class,
                                UUID.fromString(sessionId)))
                .isEqualTo(1);
    }

    @Test
    void httpRoleplayStartResumesTheLastCommittedResponseWithoutReturningTheUtterance()
            throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        MvcResult first = postRoleplayStart(activityId, UUID.randomUUID());
        UUID sessionId =
                UUID.fromString(
                        JsonPath.read(
                                first.getResponse().getContentAsString(), "$.data.sessionId"));
        UUID turnId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        ignored -> {
                            jdbc.update(
                                    """
                    INSERT INTO roleplay_turn (turn_id, session_id, request_id, idempotency_key, input_fingerprint,
                        turn_number, candidate_id, micro_goal_id, support_level, response_text, canonical_utterance,
                        checkpoint_version, committed_at)
                    VALUES (?, ?, ?, ?, ?, 1, ?, ?, 'S1', '합성 확정 서버 응답', '공개하면 안 되는 합성 원문', 1, ?)
                    """,
                                    turnId,
                                    sessionId,
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    "b".repeat(64),
                                    UUID.randomUUID(),
                                    goalId,
                                    java.sql.Timestamp.from(Instant.now()));
                            jdbc.update(
                                    """
                    UPDATE roleplay_session SET last_turn_id = ?, last_turn_number = 1,
                        current_micro_goal_id = ?, current_support_level = 'S1', row_version = 3 WHERE session_id = ?
                    """,
                                    turnId,
                                    goalId,
                                    sessionId);
                        });
        jdbc.update(
                "UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?",
                activities.findById(activityId).orElseThrow().getScenarioId());
        Map<String, Object> snapshot =
                jdbc.queryForMap("SELECT * FROM roleplay_session WHERE session_id = ?", sessionId);
        MvcResult restored =
                mvc.perform(
                                post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                        .session(childSession)
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.lastDeliveredTurnId").value(turnId.toString()))
                        .andExpect(jsonPath("$.data.checkpointVersion").value(3))
                        .andExpect(
                                jsonPath("$.data.delivery.messages[0].messageId")
                                        .value(turnId.toString()))
                        .andExpect(jsonPath("$.data.delivery.messages[0].purpose").value("RESUME"))
                        .andExpect(
                                jsonPath("$.data.delivery.messages[0].text").value("합성 확정 서버 응답"))
                        .andReturn();
        assertThat(restored.getResponse().getContentAsString()).doesNotContain("공개하면 안 되는 합성 원문");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM roleplay_session WHERE session_id = ?", sessionId))
                .isEqualTo(snapshot);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_turn WHERE session_id = ?",
                                Integer.class,
                                sessionId))
                .isEqualTo(1);
        jdbc.update(
                "UPDATE roleplay_session SET last_turn_number = 2 WHERE session_id = ?", sessionId);
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .session(childSession)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_START_DELIVERY_NOT_READY"));
    }

    @Test
    void httpRoleplayStartPreparesAnOpeningForALegacyUnstartedSession() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        var saved =
                roleplayStartsOrRestores.start(
                        activityId, UUID.randomUUID(), childAuthentication());
        Map<String, Object> snapshot =
                jdbc.queryForMap(
                        "SELECT * FROM roleplay_session WHERE session_id = ?",
                        saved.session().sessionId());
        MvcResult restored = postRoleplayStart(activityId, UUID.randomUUID());
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_message WHERE session_id = ?",
                                Integer.class,
                                saved.session().sessionId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM roleplay_session WHERE session_id = ?",
                                saved.session().sessionId()))
                .isEqualTo(snapshot);
    }

    @Test
    void httpRoleplayStartUsesTheStoredFallbackSupportForItsOpening() throws Exception {
        UUID activityId = createActivity();
        UUID assignment = assign(activityId, saveItem(QuizType.SELF_EMOTION_SITUATION, "기쁨"), 1);
        assertThat(
                        submitAnswer(
                                        assignment,
                                        "{\"expressionMatchResult\":\"NOT_ANALYZABLE\",\"cameraModelVersion\":\"camera-v1\"}")
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
        saveScenarioForActivity(activityId, "APPROVED", "L1");
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .session(childSession)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.data.delivery.messages[0].text")
                                .value("친구가 눈물을 흘리고 있어. 어떤 기분일까?"));
    }

    @ParameterizedTest(name = "시작 문구 미준비: {0}")
    @ValueSource(strings = {"MISSING", "BLANK", "NON_STRING"})
    void httpRoleplayStartWithoutApprovedTextRollsBackEverything(String missing) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "시작 문구 확인"));
        UUID scenarioId = saveScenarioForActivity(activityId, "DRAFT", "L3");
        String prompts =
                switch (missing) {
                    case "BLANK" -> "{\"S0\":\"   \"}";
                    case "NON_STRING" -> "{\"S0\":3}";
                    default -> "{}";
                };
        jdbc.update(
                "UPDATE scenario SET opening_prompts = ?::jsonb, status = 'APPROVED' WHERE scenario_id = ?",
                prompts,
                scenarioId);
        completeAssignedQuiz(activityId, true);
        UUID key = UUID.randomUUID();
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .session(childSession)
                                .with(csrf())
                                .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLEPLAY_START_DELIVERY_NOT_READY"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_request WHERE request_key = ?",
                                Integer.class,
                                key))
                .isZero();
        assertThat(resultCount(activityId)).isEqualTo(1);
    }

    @Test
    void httpRoleplayStartMessageWriteFailureRollsBackAndSameKeyRecovers() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID scenarioId = activities.findById(activityId).orElseThrow().getScenarioId();
        UUID key = UUID.randomUUID();
        jdbc.execute(
                """
                CREATE FUNCTION test_reject_start_message() RETURNS trigger AS $$
                BEGIN
                    IF NEW.scenario_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'Synthetic message failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER test_start_message_failure BEFORE INSERT ON roleplay_start_message
                    FOR EACH ROW EXECUTE FUNCTION test_reject_start_message();
                """
                        .formatted(scenarioId));
        try {
            assertThat(postRoleplayStart(activityId, key).getResponse().getStatus()).isEqualTo(500);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                    Integer.class,
                                    activityId))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM roleplay_start_request WHERE request_key = ?",
                                    Integer.class,
                                    key))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM roleplay_start_message WHERE scenario_id = ?",
                                    Integer.class,
                                    scenarioId))
                    .isZero();
        } finally {
            jdbc.execute(
                    "DROP TRIGGER test_start_message_failure ON roleplay_start_message; DROP FUNCTION test_reject_start_message()");
        }
        assertThat(postRoleplayStart(activityId, key).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void httpRoleplayStartRequiresCsrfAndTheOwningChild() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID key = UUID.randomUUID();
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .session(childSession)
                                .header("Idempotency-Key", key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .with(csrf())
                                .header("Idempotency-Key", key))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .with(TestInstructors.instructor("teacher-quiz"))
                                .with(csrf())
                                .header("Idempotency-Key", key))
                .andExpect(status().isForbidden());
        Authentication other =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(UUID.randomUUID()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .with(authentication(other))
                                .with(csrf())
                                .header("Idempotency-Key", key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        jdbc.update("UPDATE child SET status = 'PAUSED' WHERE child_id = ?", childId);
        assertThat(postRoleplayStart(activityId, key).getResponse().getStatus()).isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isZero();
    }

    @ParameterizedTest(name = "잘못된 시작 요청: {0}")
    @ValueSource(strings = {"MISSING_KEY", "INVALID_KEY", "BODY", "EMPTY_JSON", "INVALID_ACTIVITY"})
    void httpRoleplayStartRejectsInvalidHeadersAndBodies(String invalid) throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        var request =
                post(
                                "/api/v1/activities/{id}/roleplay-sessions",
                                invalid.equals("INVALID_ACTIVITY") ? "not-a-uuid" : activityId)
                        .session(childSession)
                        .with(csrf());
        if (!invalid.equals("MISSING_KEY"))
            request.header(
                    "Idempotency-Key",
                    invalid.equals("INVALID_KEY") ? "bad-key" : UUID.randomUUID());
        if (invalid.equals("BODY") || invalid.equals("EMPTY_JSON"))
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(invalid.equals("BODY") ? "{\"initialSupportLevel\":\"S3\"}" : "{}");
        mvc.perform(request).andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_session WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isZero();
    }

    @ParameterizedTest(name = "재질문하지 않는 활동 상태: {0}")
    @ValueSource(strings = {"PAUSED", "COMPLETED", "RECOVERY_NEEDED"})
    void httpRoleplayStartDoesNotSendQuestionsForStoppedStates(String state) throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        var created =
                roleplayStartsOrRestores.start(
                        activityId, UUID.randomUUID(), childAuthentication());
        String sessionState = state.equals("RECOVERY_NEEDED") ? "IN_PROGRESS" : state;
        jdbc.update(
                "UPDATE roleplay_session SET status = ? WHERE session_id = ?",
                sessionState,
                created.session().sessionId());
        jdbc.update("UPDATE activity SET status = ? WHERE activity_id = ?", state, activityId);
        mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .session(childSession)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(sessionState))
                .andExpect(jsonPath("$.data.activityStatus").value(state))
                .andExpect(jsonPath("$.data.delivery.messages").isEmpty());
    }

    @ParameterizedTest(name = "동시 HTTP 시작의 동일 키 여부: {0}")
    @ValueSource(booleans = {true, false})
    void concurrentHttpRoleplayStartsReturnOneSessionAndOneOpening(boolean sameKey)
            throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = sameKey ? firstKey : UUID.randomUUID();
        Callable<MvcResult> first = () -> postRoleplayStart(activityId, firstKey);
        Callable<MvcResult> second = () -> postRoleplayStart(activityId, secondKey);
        var responses = concurrentlyWithChildLocked(List.of(first, second));
        assertThat(responses.stream().map(response -> response.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(201, 200);
        Object firstData =
                JsonPath.read(responses.getFirst().getResponse().getContentAsString(), "$.data");
        Object secondData =
                JsonPath.read(responses.getLast().getResponse().getContentAsString(), "$.data");
        assertThat(firstData).isEqualTo(secondData);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_message m JOIN roleplay_session s ON s.session_id = m.session_id WHERE s.activity_id = ?",
                                Integer.class,
                                activityId))
                .isEqualTo(1);
    }

    private MvcResult postRoleplayStart(UUID activityId, UUID key) throws Exception {
        return mvc.perform(
                        post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                .session(childSession)
                                .with(csrf())
                                .header("Idempotency-Key", key))
                .andReturn();
    }

    @Test
    void deletingSessionAlsoDeletesItsStartRequestKeys() throws Exception {
        UUID activityId = readyRoleplayActivity(childId);
        var created =
                roleplayStartsOrRestores.start(
                        activityId, UUID.randomUUID(), childAuthentication());
        assertThat(postRoleplayStart(activityId, UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(200);
        roleplaySessions.deleteById(created.session().sessionId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_request WHERE activity_id = ?",
                                Integer.class,
                                activityId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM roleplay_start_message WHERE session_id = ?",
                                Integer.class,
                                created.session().sessionId()))
                .isZero();
    }

    private UUID readyRoleplayActivity(UUID ownerId) throws Exception {
        UUID activityId = assignFirstActivity(ownerId, Map.of("title", "역할극 시작 준비"));
        saveScenarioForActivity(activityId, "APPROVED", "L3");
        completeAssignedQuiz(activityId, true);
        return activityId;
    }

    private RoleplaySession saveInitialSession(InitialRoleplayStartInput input) {
        var evidence = input.initialDifficulty();
        return roleplaySessions.saveAndFlush(
                RoleplaySession.start(
                        UUID.randomUUID(),
                        evidence.activityId(),
                        evidence.childId(),
                        input.scenario().scenarioId(),
                        input.scenario().version(),
                        evidence.initialSupportLevel(),
                        Instant.now()));
    }

    private UUID saveScenarioForActivity(UUID activityId, String status, String level) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO scenario (scenario_id, status, scenario_level, goal_content_hash,
                    class_id, scenario_version, title, scenario_facts, opening_prompts, origin, created_at)
                SELECT ?, ?, ?, g.content_hash, c.class_id, 1, '합성 승인 시나리오', '[]'::jsonb,
                    '{"S0":"친구는 어떤 기분일까?","S1":"친구가 눈물을 흘리고 있어. 어떤 기분일까?","S2":"친구가 슬퍼 보여. 마음을 함께 말해볼까?"}'::jsonb,
                    'PREPARED', ?
                FROM activity a JOIN child c ON c.child_id = a.child_id
                    JOIN learning_goal g ON g.goal_id = a.goal_id
                WHERE a.activity_id = ?
                """,
                id,
                status,
                level,
                java.sql.Timestamp.from(Instant.now()),
                activityId);
        return id;
    }

    private UUID assignFirstActivity(UUID targetChildId, Map<String, Object> goal)
            throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/activities")
                                        .with(TestInstructors.instructor("teacher-quiz"))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "childId", targetChildId,
                                                                "activityType", "FIRST_COMBINED",
                                                                "goal", goal))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.activityId"));
    }

    private void completeAssignedQuiz(UUID activityId, boolean correct) throws Exception {
        completeAssignedQuiz(activityId, correct ? Integer.MAX_VALUE : 0);
    }

    private void completeAssignedQuiz(UUID activityId, int correctCount) throws Exception {
        UUID ownerId = activities.findById(activityId).orElseThrow().getChildId();
        Authentication owner =
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(ownerId),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
        int answered = 0;
        for (ActivityQuiz question : assignments.findByActivityIdOrderByQuestionOrder(activityId)) {
            QuizItem item = items.findById(question.getItemId()).orElseThrow();
            boolean correct = answered++ < correctCount;
            String answer =
                    correct
                            ? item.getCorrectAnswer()
                            : item.getChoices().stream()
                                    .filter(choice -> !choice.equals(item.getCorrectAnswer()))
                                    .filter(choice -> !item.getAcceptableAnswers().contains(choice))
                                    .findFirst()
                                    .orElseThrow();
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("firstResponse", answer);
            request.put("finalResponse", answer);
            if (item.getQuizType() == QuizType.SELF_EMOTION_SITUATION) {
                request.put("expressionMatchResult", "MATCH");
                request.put("cameraModelVersion", "camera-test-v1");
            }
            mvc.perform(
                            put(
                                            "/api/v1/activity-quiz-items/{id}/attempt",
                                            question.getActivityQuizId())
                                    .with(authentication(owner))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    private void assertScenarioPreparationNotReady(UUID activityId, String code) {
        assertThatThrownBy(
                        () ->
                                scenarioPreparations.prepare(
                                        activityId,
                                        instructorAuthentication(
                                                TestInstructors.id("teacher-quiz"))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.getCode()).isEqualTo(code);
                        });
    }

    private void assertInitialDifficultyNotReady(UUID activityId) {
        assertThatThrownBy(
                        () ->
                                initialDifficulties.requireForActivity(
                                        activityId, childAuthentication()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.getCode()).isEqualTo("INITIAL_DIFFICULTY_NOT_READY");
                        });
    }

    private Authentication childAuthentication() {
        SecurityContext context =
                (SecurityContext)
                        childSession.getAttribute(
                                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context.getAuthentication();
    }

    private Authentication instructorAuthentication(UUID instructorId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(instructorId, UUID.randomUUID(), UserRole.INSTRUCTOR),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")));
    }

    private <T> List<T> concurrentlyWithChildLocked(List<Callable<T>> requests) throws Exception {
        return concurrentlyWithDatabaseLock(requests, false);
    }

    private <T> List<T> concurrentlyWithDatabaseLock(
            List<Callable<T>> requests, boolean startRequestTable) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> request : requests) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                    return request.call();
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            // 두 요청이 실제 DB 잠금 대기에 들어간 것을 확인한 뒤 경합을 해제한다.
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            transaction -> {
                                if (startRequestTable) {
                                    jdbc.execute("LOCK TABLE roleplay_start_request IN SHARE MODE");
                                } else {
                                    jdbc.queryForObject(
                                            "SELECT child_id FROM child WHERE child_id = ? FOR UPDATE",
                                            UUID.class,
                                            childId);
                                }
                                start.countDown();
                                await().atMost(10, TimeUnit.SECONDS)
                                        .until(
                                                () ->
                                                        jdbc.queryForObject(
                                                                "SELECT count(*) FROM pg_stat_activity"
                                                                        + " WHERE datname = current_database()"
                                                                        + " AND wait_event_type = 'Lock'"
                                                                        + (startRequestTable
                                                                                ? " AND query LIKE '%insert into roleplay_start_request%'"
                                                                                : " AND (query LIKE '%from activity %' OR query LIKE '%from child %')"),
                                                                Integer.class),
                                                waiting -> waiting == requests.size());
                            });
            List<T> responses = new ArrayList<>();
            for (Future<T> future : futures) {
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

    @TestConfiguration(proxyBeanMethods = false)
    static class CompletionEventConfiguration {
        @Bean
        CompletionEvents completionEvents() {
            return new CompletionEvents();
        }
    }

    static class CompletionEvents {
        private final List<FirstQuizCompleted> committed = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onCompleted(FirstQuizCompleted event) {
            committed.add(event);
        }

        List<FirstQuizCompleted> forActivity(UUID activityId) {
            return committed.stream()
                    .filter(event -> event.activityId().equals(activityId))
                    .toList();
        }
    }
}
