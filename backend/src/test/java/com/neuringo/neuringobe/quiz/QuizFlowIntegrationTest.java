package com.neuringo.neuringobe.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @Autowired private InitialRoleplayStartQueryService roleplayStarts;
    @Autowired private RoleplaySessionRepository roleplaySessions;
    @Autowired private RoleplayRequestScopeService roleplayScopes;
    @Autowired private RoleplayStartService roleplayStartsOrRestores;

    private UUID childId;
    private MockHttpSession childSession;

    @BeforeEach
    void setUp() throws Exception {
        // 시나리오는 학급에 묶지 않는 공용 자료라, 앞 테스트의 승인본이 같은 목표의 재사용 후보로 잡히지 않게 비운다.
        jdbc.update("DELETE FROM scenario");
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
                                prepared.learningGoal().contentHash(), "L3"));
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
    @ValueSource(strings = {"SAME", "OTHER_CLASS", "GOAL", "LEVEL"})
    void scenarioReuseKeyIsGoalHashAndLevelOnly(String difference) throws Exception {
        Map<String, Object> firstGoal = Map.of("title", "친구 마음 알아보기");
        UUID firstActivity = assignFirstActivity(childId, firstGoal);
        completeAssignedQuiz(firstActivity, true);
        UUID classId = children.findById(childId).orElseThrow().getClassId();
        if (difference.equals("OTHER_CLASS")) {
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

        // 시나리오는 공용 자료라 다른 반이어도 같은 키다.
        if (difference.equals("SAME") || difference.equals("OTHER_CLASS")) {
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
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
        approveScenarioFor(activityId, scenarioId);
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
    void committedFirstQuizWaitsForInstructorApprovalEvenWithMatchingApprovedScenario()
            throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "승인 대기"));
        saveScenarioForActivity(activityId, "APPROVED", "L3");
        completeAssignedQuiz(activityId, true);

        // 재사용 후보가 있어도 담당 강사가 승인해야 붙는다.
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
        assertThat(completionEvents.forActivity(activityId)).hasSize(1);
    }

    @Test
    void ownerInstructorPagesThroughReuseCandidatesNewestApprovalFirst() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "후보 넘기기"));
        completeAssignedQuiz(activityId, true);
        // 작은 목표는 초안일 때만 넣을 수 있다.
        UUID older = saveScenarioForActivity(activityId, "DRAFT", "L3");
        UUID microGoal = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence) VALUES (?, ?, 1, '감정 말하기', '[\"슬프다고 말함\"]'::jsonb)",
                microGoal,
                older);
        approve(older, Instant.parse("2026-10-01T00:00:00Z"));
        UUID newer = saveScenarioForActivity(activityId, "DRAFT", "L3");
        approve(newer, Instant.parse("2026-10-02T00:00:00Z"));

        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .with(TestInstructors.instructor("teacher-quiz")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenario.scenarioId").value(newer.toString()))
                .andExpect(jsonPath("$.data.scenario.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .param("after", newer.toString())
                                .with(TestInstructors.instructor("teacher-quiz")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenario.scenarioId").value(older.toString()))
                .andExpect(
                        jsonPath("$.data.scenario.microGoals[0].microGoalId")
                                .value(microGoal.toString()))
                .andExpect(
                        jsonPath("$.data.scenario.microGoals[0].requiredEvidence[0]")
                                .value("슬프다고 말함"))
                // 다른 아동이 언제·몇 번 썼는지는 내보내지 않는다.
                .andExpect(jsonPath("$.data.scenario.lastUsedAt").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(false));
        // 그사이 후보에서 빠진 after 를 보내면 처음부터 보여 준다.
        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .param("after", UUID.randomUUID().toString())
                                .with(TestInstructors.instructor("teacher-quiz")))
                .andExpect(jsonPath("$.data.scenario.scenarioId").value(newer.toString()));
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
    }

    @Test
    void reuseCandidateIsEmptyWhenNothingMatches() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "후보 없음"));
        completeAssignedQuiz(activityId, true);
        saveScenarioForActivity(activityId, "APPROVED", "L1");
        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .with(TestInstructors.instructor("teacher-quiz")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenario").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void approvingCandidateAttachesItOnceAndRoleplayCanStart() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "후보 승인"));
        completeAssignedQuiz(activityId, true);
        UUID chosen = saveScenarioForActivity(activityId, "APPROVED", "L3");
        UUID other = saveScenarioForActivity(activityId, "APPROVED", "L3");

        for (int i = 0; i < 2; i++) {
            putScenario(activityId, chosen, "teacher-quiz")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scenarioId").value(chosen.toString()));
        }
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isEqualTo(chosen);
        assertThat(
                        roleplayStarts
                                .requireForActivity(activityId, childAuthentication())
                                .scenario()
                                .scenarioId())
                .isEqualTo(chosen);
        putScenario(activityId, other, "teacher-quiz")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_ALREADY_ATTACHED"));
        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .with(TestInstructors.instructor("teacher-quiz")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_ALREADY_ATTACHED"));
    }

    @ParameterizedTest(name = "붙일 수 없는 후보: {0}")
    @ValueSource(strings = {"DRAFT", "REJECTED", "LEVEL", "USED", "MISSING"})
    void approvingNonCandidateIsRejectedWithoutAttaching(String reason) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "후보 아님"));
        completeAssignedQuiz(activityId, true);
        UUID scenarioId =
                switch (reason) {
                    case "DRAFT" -> saveScenarioForActivity(activityId, "DRAFT", "L3");
                    case "REJECTED" -> saveScenarioForActivity(activityId, "REJECTED", "L3");
                    case "LEVEL" -> saveScenarioForActivity(activityId, "APPROVED", "L1");
                    case "MISSING" -> UUID.randomUUID();
                    default -> {
                        UUID used = saveScenarioForActivity(activityId, "APPROVED", "L3");
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
                                used,
                                previous.getActivityId());
                        yield used;
                    }
                };
        putScenario(activityId, scenarioId, "teacher-quiz")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_NOT_REUSABLE"));
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
    }

    @Test
    void onlyOwningInstructorSeesAndApprovesCandidatesAfterQuiz() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "후보 권한"));
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        // 퀴즈로 난이도가 정해지기 전에는 후보를 고를 수 없다.
        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .with(TestInstructors.instructor("teacher-quiz")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INITIAL_DIFFICULTY_NOT_READY"));
        completeAssignedQuiz(activityId, true);

        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .with(TestInstructors.instructor("teacher-other")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        putScenario(activityId, scenarioId, "teacher-other").andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/activities/{id}/scenario-candidate", activityId)
                                .session(childSession))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put("/api/v1/activities/{id}/scenario", activityId)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"scenarioId\":\"" + scenarioId + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
    }

    private org.springframework.test.web.servlet.ResultActions putScenario(
            UUID activityId, UUID scenarioId, String instructor) throws Exception {
        return mvc.perform(
                put("/api/v1/activities/{id}/scenario", activityId)
                        .with(TestInstructors.instructor(instructor))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"" + scenarioId + "\"}"));
    }

    /** 승인 시각을 정해 승인한다. 같은 시각에 넣은 후보끼리의 순서를 고정하려고 쓴다. */
    private void approve(UUID scenarioId, Instant approvedAt) {
        jdbc.update(
                "UPDATE scenario SET status = 'APPROVED', approved_at = ? WHERE scenario_id = ?",
                java.sql.Timestamp.from(approvedAt),
                scenarioId);
    }

    @Test
    void editingCandidateCreatesGoalBoundDraftOnceThenApprovalAttachesItAndKeepsOriginal()
            throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "고쳐서 쓰기"));
        completeAssignedQuiz(activityId, true);
        UUID goalId = activities.findById(activityId).orElseThrow().getGoalId();
        UUID original = candidateWithMicroGoal(activityId);
        UUID originalGoal =
                jdbc.queryForObject(
                        "SELECT micro_goal_id FROM scenario_micro_goal WHERE scenario_id = ?",
                        UUID.class,
                        original);

        String created =
                postVersion(
                                original,
                                "{\"version\":1,\"goalId\":\"" + goalId + "\",\"title\":\"고친 제목\"}")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.status").value("DRAFT"))
                        .andExpect(jsonPath("$.data.version").value(2))
                        .andExpect(jsonPath("$.data.goalId").value(goalId.toString()))
                        .andExpect(jsonPath("$.data.scenarioSource").value("NEWLY_GENERATED"))
                        .andExpect(jsonPath("$.data.title").value("고친 제목"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID draft = UUID.fromString(JsonPath.read(created, "$.data.scenarioId"));
        String draftGoal = JsonPath.read(created, "$.data.microGoals[0].microGoalId");
        assertThat(draftGoal).isNotEqualTo(originalGoal.toString());
        // 같은 목표·같은 원본으로 다시 보내면 만든 초안을 그대로 준다.
        postVersion(original, "{\"version\":1,\"goalId\":\"" + goalId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarioId").value(draft.toString()));
        // 초안은 활동에 붙지 않아 역할극이 열리지 않는다.
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();

        patchScenario(
                        draft,
                        "{\"version\":2,\"summary\":\"친구가 그림을 망쳐 속상하다.\",\"microGoals\":[{\"microGoalId\":\""
                                + draftGoal
                                + "\",\"description\":\"위로하는 말을 한다\",\"requiredEvidence\":[\"괜찮냐고 물음\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("친구가 그림을 망쳐 속상하다."))
                .andExpect(jsonPath("$.data.microGoals[0].requiredEvidence[0]").value("괜찮냐고 물음"));
        patchScenario(draft, "{\"version\":2,\"status\":\"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.goalId").value(goalId.toString()));

        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isEqualTo(draft);
        assertThat(
                        roleplayStarts
                                .requireForActivity(activityId, childAuthentication())
                                .scenario()
                                .scenarioId())
                .isEqualTo(draft);
        // 승인본은 공용이라 목표에서 떨어지고, 원본도 그대로 후보로 남는다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT goal_id FROM scenario WHERE scenario_id = ?",
                                UUID.class,
                                draft))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                original))
                .isEqualTo("APPROVED");
    }

    @Test
    void rejectedDraftStaysAsRecordAndActivityWaitsAgainWhileDiscardedDraftIsDeleted()
            throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "반려와 폐기"));
        completeAssignedQuiz(activityId, true);
        UUID goalId = activities.findById(activityId).orElseThrow().getGoalId();
        UUID original = candidateWithMicroGoal(activityId);

        UUID discarded = draftFrom(original, goalId);
        mvc.perform(
                        delete("/api/v1/scenarios/{id}", discarded)
                                .with(TestInstructors.instructor("teacher-quiz"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE scenario_id = ?",
                                Long.class,
                                discarded))
                .isZero();

        UUID rejected = draftFrom(original, goalId);
        patchScenario(rejected, "{\"version\":2,\"status\":\"REJECTED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
        patchScenario(rejected, "{\"version\":2,\"title\":\"다시\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_NOT_EDITABLE"));
        mvc.perform(
                        delete("/api/v1/scenarios/{id}", rejected)
                                .with(TestInstructors.instructor("teacher-quiz"))
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_IN_USE"));
        // 반려 뒤에도 같은 목표로 다시 고칠 수 있다.
        draftFrom(original, goalId);
    }

    @ParameterizedTest(name = "초안 검증: {0}")
    @ValueSource(
            strings = {
                "FORBIDDEN",
                "BLANK",
                "UNKNOWN_GOAL",
                "REVISION",
                "VERSION",
                "NO_GOAL_ID",
                "OTHER_SOURCE"
            })
    void draftEditsAreValidated(String broken) throws Exception {
        UUID activityId =
                assignFirstActivity(
                        childId, Map.of("title", "초안 검증", "forbiddenExpressions", List.of("바보")));
        completeAssignedQuiz(activityId, true);
        UUID goalId = activities.findById(activityId).orElseThrow().getGoalId();
        UUID original = candidateWithMicroGoal(activityId);
        if (broken.equals("NO_GOAL_ID")) {
            postVersion(original, "{\"version\":1}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
            return;
        }
        UUID draft = draftFrom(original, goalId);
        var result =
                switch (broken) {
                    case "FORBIDDEN" ->
                            patchScenario(draft, "{\"version\":2,\"title\":\"바보 같은 친구\"}");
                    case "BLANK" -> patchScenario(draft, "{\"version\":2,\"summary\":\"   \"}");
                    case "UNKNOWN_GOAL" ->
                            patchScenario(
                                    draft,
                                    "{\"version\":2,\"microGoals\":[{\"microGoalId\":\""
                                            + UUID.randomUUID()
                                            + "\",\"description\":\"설명\",\"requiredEvidence\":[\"증거\"]}]}");
                    case "REVISION" ->
                            patchScenario(
                                    draft, "{\"version\":2,\"status\":\"REVISION_REQUIRED\"}");
                    case "VERSION" -> patchScenario(draft, "{\"version\":1,\"title\":\"제목\"}");
                    default -> {
                        UUID another = candidateWithMicroGoal(activityId);
                        yield postVersion(another, "{\"version\":1,\"goalId\":\"" + goalId + "\"}");
                    }
                };
        switch (broken) {
            case "REVISION" ->
                    result.andExpect(status().isConflict())
                            .andExpect(
                                    jsonPath("$.error.code")
                                            .value("SCENARIO_REGENERATION_UNAVAILABLE"));
            case "VERSION" ->
                    result.andExpect(status().isConflict())
                            .andExpect(jsonPath("$.error.code").value("SCENARIO_VERSION_MISMATCH"));
            case "OTHER_SOURCE" ->
                    result.andExpect(status().isConflict())
                            .andExpect(jsonPath("$.error.code").value("SCENARIO_DRAFT_EXISTS"));
            default ->
                    result.andExpect(status().isUnprocessableContent())
                            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scenario WHERE scenario_id = ?",
                                String.class,
                                draft))
                .isEqualTo("DRAFT");
    }

    @Test
    void draftsAreHiddenFromOtherInstructorsAndDeletedWithTheChild() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "초안 권한"));
        completeAssignedQuiz(activityId, true);
        UUID goalId = activities.findById(activityId).orElseThrow().getGoalId();
        UUID original = candidateWithMicroGoal(activityId);
        UUID draft = draftFrom(original, goalId);

        mvc.perform(
                        get("/api/v1/scenarios/{id}", draft)
                                .with(TestInstructors.instructor("teacher-other")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_NOT_FOUND"));
        mvc.perform(
                        patch("/api/v1/scenarios/{id}", draft)
                                .with(TestInstructors.instructor("teacher-other"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":2,\"status\":\"APPROVED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_NOT_FOUND"));
        mvc.perform(
                        delete("/api/v1/scenarios/{id}", draft)
                                .with(TestInstructors.instructor("teacher-other"))
                                .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/scenarios/{id}/versions", original)
                                .with(TestInstructors.instructor("teacher-other"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":1,\"goalId\":\"" + goalId + "\"}"))
                .andExpect(status().isNotFound());
        // 공용 승인본은 어느 강사나 본다. 목표가 없어 goalId 는 비어 있다.
        mvc.perform(
                        get("/api/v1/scenarios/{id}", original)
                                .with(TestInstructors.instructor("teacher-other")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.goalId").doesNotExist());
        mvc.perform(
                        get("/api/v1/scenarios/{id}", draft)
                                .with(TestInstructors.instructor("teacher-quiz")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.goalId").value(goalId.toString()));

        mvc.perform(
                        delete("/api/v1/children/{id}", childId)
                                .with(TestInstructors.instructor("teacher-quiz"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE scenario_id = ?",
                                Long.class,
                                draft))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scenario WHERE scenario_id = ?",
                                Long.class,
                                original))
                .isEqualTo(1);
    }

    /**
     * 고치며 승인하는 요청과 아동 삭제가 겹쳐도 서로 기다리다 멈추지 않는다. 삭제는 활동 → 아동 → (목표 CASCADE 로) 초안 순서로 잠그므로, 승인도 활동을 먼저
     * 잡고 나서 초안을 고쳐야 한다.
     */
    @Test
    void approvingWithEditsLocksActivityBeforeDraftSoChildDeletionDoesNotDeadlock()
            throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "승인과 삭제"));
        completeAssignedQuiz(activityId, true);
        UUID goalId = activities.findById(activityId).orElseThrow().getGoalId();
        UUID draft = draftFrom(candidateWithMicroGoal(activityId), goalId);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> responses = new ArrayList<>();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            holding -> {
                                jdbc.queryForObject(
                                        "SELECT activity_id FROM activity WHERE activity_id = ? FOR UPDATE",
                                        UUID.class,
                                        activityId);
                                responses.add(
                                        pool.submit(
                                                () ->
                                                        patchScenario(
                                                                        draft,
                                                                        "{\"version\":2,\"title\":\"삭제와 겹친 승인\",\"status\":\"APPROVED\"}")
                                                                .andReturn()
                                                                .getResponse()
                                                                .getStatus()));
                                awaitActivityLockWaiters(1);
                                // 승인은 활동을 기다리는 동안 초안 행을 아직 잡지 않았다.
                                assertThat(scenarioRowIsFree(pool, draft)).isTrue();
                                responses.add(
                                        pool.submit(
                                                () ->
                                                        mvc.perform(
                                                                        delete(
                                                                                        "/api/v1/children/{id}",
                                                                                        childId)
                                                                                .with(
                                                                                        TestInstructors
                                                                                                .instructor(
                                                                                                        "teacher-quiz"))
                                                                                .with(csrf()))
                                                                .andReturn()
                                                                .getResponse()
                                                                .getStatus()));
                                awaitActivityLockWaiters(2);
                            });
            // 승인이 먼저 잡으면 승인 뒤 삭제되고, 삭제가 먼저 잡으면 승인은 사라진 활동을 404 로 받는다.
            assertThat(responses.get(0).get(20, TimeUnit.SECONDS)).isIn(200, 404);
            assertThat(responses.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(204);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitActivityLockWaiters(int expected) {
        awaitLockWaiters("%from activity %", expected);
    }

    private void awaitLockWaiters(String queryPattern, int expected) {
        await().atMost(10, TimeUnit.SECONDS)
                .until(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_stat_activity"
                                                + " WHERE datname = current_database()"
                                                + " AND wait_event_type = 'Lock'"
                                                + " AND query LIKE ?",
                                        Integer.class,
                                        queryPattern),
                        waiting -> waiting == expected);
    }

    /** 다른 연결에서 초안 행을 잠글 수 있는지 본다. 이미 누가 잡고 있으면 건너뛰어 0 행이 된다. */
    private boolean scenarioRowIsFree(ExecutorService pool, UUID scenarioId) {
        try {
            return pool.submit(
                                    () ->
                                            new TransactionTemplate(transactionManager)
                                                    .execute(
                                                            probe ->
                                                                    jdbc.queryForObject(
                                                                            "SELECT count(*) FROM (SELECT 1 FROM scenario WHERE scenario_id = ? FOR UPDATE SKIP LOCKED) s",
                                                                            Integer.class,
                                                                            scenarioId)))
                            .get(10, TimeUnit.SECONDS)
                    == 1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 작은 목표가 하나 있는 재사용 후보를 만든다. 작은 목표는 초안일 때만 넣을 수 있다. */
    private UUID candidateWithMicroGoal(UUID activityId) {
        UUID id = saveScenarioForActivity(activityId, "DRAFT", "L3");
        jdbc.update(
                "INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence) VALUES (?, ?, 1, '감정 말하기', '[\"슬프다고 말함\"]'::jsonb)",
                UUID.randomUUID(),
                id);
        jdbc.update("UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ?", id);
        return id;
    }

    private UUID draftFrom(UUID original, UUID goalId) throws Exception {
        String body =
                postVersion(original, "{\"version\":1,\"goalId\":\"" + goalId + "\"}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.scenarioId"));
    }

    private org.springframework.test.web.servlet.ResultActions postVersion(UUID source, String json)
            throws Exception {
        return mvc.perform(
                post("/api/v1/scenarios/{id}/versions", source)
                        .with(TestInstructors.instructor("teacher-quiz"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    private org.springframework.test.web.servlet.ResultActions patchScenario(
            UUID scenarioId, String json) throws Exception {
        return mvc.perform(
                patch("/api/v1/scenarios/{id}", scenarioId)
                        .with(TestInstructors.instructor("teacher-quiz"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    @Test
    void approvalInboxListsOwnWaitingActivitiesWithTheirState() throws Exception {
        // 같은 강사로 도는 다른 테스트의 활동도 목록에 있으므로 이 테스트의 활동만 골라 본다.
        UUID secondChild = UUID.randomUUID();
        children.save(
                new Child(
                        secondChild,
                        children.findById(childId).orElseThrow().getClassId(),
                        "두 번째 아동",
                        ChildStatus.ACTIVE));
        UUID beforeQuiz = assignFirstActivity(secondChild, Map.of("title", "후보 있음"));
        UUID waiting = assignFirstActivity(childId, Map.of("title", "후보 있음"));
        completeAssignedQuiz(waiting, true);
        UUID candidate = candidateWithMicroGoal(waiting);

        // 퀴즈를 마치지 않은 활동은 아직 기다리는 활동이 아니다.
        assertThat(inboxRow(beforeQuiz, "teacher-quiz")).isEmpty();
        Map<String, Object> row = inboxRow(waiting, "teacher-quiz").orElseThrow();
        assertThat(row)
                .containsEntry("childName", "퀴즈 아동")
                .containsEntry("goalTitle", "후보 있음")
                .containsEntry("scenarioLevel", "L3")
                .containsEntry("state", "CANDIDATE_AVAILABLE");
        assertThat(row.get("draftScenarioId")).isNull();

        UUID goalId = activities.findById(waiting).orElseThrow().getGoalId();
        UUID draft = draftFrom(candidate, goalId);
        assertThat(inboxRow(waiting, "teacher-quiz").orElseThrow())
                .containsEntry("state", "DRAFT_IN_PROGRESS")
                .containsEntry("draftScenarioId", draft.toString())
                .containsEntry("draftVersion", 2);
        mvc.perform(
                        delete("/api/v1/scenarios/{id}", draft)
                                .with(TestInstructors.instructor("teacher-quiz"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(inboxRow(waiting, "teacher-quiz").orElseThrow())
                .containsEntry("state", "CANDIDATE_AVAILABLE");

        // 다른 강사에게는 보이지 않는다.
        assertThat(inboxRow(waiting, "teacher-other")).isEmpty();

        putScenario(waiting, candidate, "teacher-quiz").andExpect(status().isOk());
        assertThat(inboxRow(waiting, "teacher-quiz")).isEmpty();
    }

    @Test
    void approvalInboxShowsActivityWithoutAnyCandidate() throws Exception {
        UUID waiting = assignFirstActivity(childId, Map.of("title", "후보 없음"));
        completeAssignedQuiz(waiting, true);
        assertThat(inboxRow(waiting, "teacher-quiz").orElseThrow())
                .containsEntry("state", "NO_CANDIDATE");
    }

    private java.util.Optional<Map<String, Object>> inboxRow(UUID activityId, String instructor)
            throws Exception {
        String body =
                mvc.perform(
                                get("/api/v1/scenario-approvals")
                                        .with(TestInstructors.instructor(instructor)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        List<Map<String, Object>> rows =
                JsonPath.read(body, "$.data[?(@.activityId == '" + activityId + "')]");
        return rows.stream().findFirst();
    }

    @ParameterizedTest(name = "승인 후보 제외 조건: {0}")
    @ValueSource(
            strings = {
                "GOAL",
                "LEVEL",
                "DRAFT",
                "REVISION_REQUIRED",
                "REJECTED",
                "UNSCOPED",
                "USED"
            })
    void firstQuizDoesNotAttachIneligibleScenario(String mismatch) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "조건에 맞는 승인본만"));
        String status =
                List.of("DRAFT", "REVISION_REQUIRED", "REJECTED").contains(mismatch)
                        ? mismatch
                        : "APPROVED";
        // 승인 전 초안에서만 조건을 수정한다.
        UUID scenarioId =
                saveScenarioForActivity(
                        activityId, "DRAFT", mismatch.equals("LEVEL") ? "L1" : "L3");
        if (mismatch.equals("GOAL")) {
            jdbc.update(
                    "UPDATE scenario SET goal_content_hash = ? WHERE scenario_id = ?",
                    "0".repeat(64),
                    scenarioId);
        } else if (mismatch.equals("UNSCOPED")) {
            jdbc.update(
                    "UPDATE scenario SET goal_content_hash = null WHERE scenario_id = ?",
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
                        "scenario_version = 2",
                        "approved_at = now() - interval '1 day'",
                        "status = 'DRAFT'",
                        "status = 'REJECTED'")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE scenario SET "
                                                    + change
                                                    + " WHERE scenario_id = ?",
                                            scenarioId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @ParameterizedTest(name = "판정이 끝난 초안은 다시 바꿀 수 없음: {0}")
    @ValueSource(strings = {"REVISION_REQUIRED", "REJECTED"})
    void reviewedDraftCannotChangeOrBeApproved(String reviewed) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "판정 뒤 불변"));
        UUID scenarioId = saveScenarioForActivity(activityId, "DRAFT", "L3");
        jdbc.update("UPDATE scenario SET status = ? WHERE scenario_id = ?", reviewed, scenarioId);
        for (String change : List.of("title = '변경'", "status = 'APPROVED'", "status = 'DRAFT'")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE scenario SET "
                                                    + change
                                                    + " WHERE scenario_id = ?",
                                            scenarioId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    void approvingDraftFillsApprovedAtOnce() throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "승인 시각"));
        UUID scenarioId = saveScenarioForActivity(activityId, "DRAFT", "L3");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT approved_at FROM scenario WHERE scenario_id = ?",
                                Instant.class,
                                scenarioId))
                .isNull();
        jdbc.update("UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ?", scenarioId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT approved_at FROM scenario WHERE scenario_id = ?",
                                Instant.class,
                                scenarioId))
                .isNotNull();
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
    void unauthorizedResultReadDoesNotRepublishCompletionAndOwnerReadDoesNotAttach()
            throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "결과 복구 권한"));
        completeAssignedQuiz(activityId, true);
        saveScenarioForActivity(activityId, "APPROVED", "L3");
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
        // 결과 조회는 알림만 다시 남기고, 시나리오는 담당 강사가 승인해야 붙는다.
        assertThat(completionEvents.forActivity(activityId)).hasSize(delivered + 1);
        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isNull();
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
        approveScenarioFor(activityId, scenarioId);
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
    @ValueSource(
            strings = {
                "UNLINKED",
                "DRAFT",
                "REVISION_REQUIRED",
                "REJECTED",
                "GOAL",
                "LEVEL",
                "COMPLETED"
            })
    void initialRoleplayInputRejectsUnreadyOrMismatchedApproval(String mismatch) throws Exception {
        UUID activityId = assignFirstActivity(childId, Map.of("title", "시작 근거 확인"));
        completeAssignedQuiz(activityId, true);
        UUID scenarioId =
                saveScenarioForActivity(
                        activityId, "DRAFT", mismatch.equals("LEVEL") ? "L1" : "L3");
        if (mismatch.equals("GOAL")) {
            jdbc.update(
                    "UPDATE scenario SET goal_content_hash = ? WHERE scenario_id = ?",
                    "0".repeat(64),
                    scenarioId);
        }
        String status =
                List.of("DRAFT", "REVISION_REQUIRED", "REJECTED").contains(mismatch)
                        ? mismatch
                        : "APPROVED";
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
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L1");
        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());
        approveScenarioFor(activityId, scenarioId);
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
        approveScenarioFor(activityId, scenarioId);
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
        UUID scenarioId = saveScenarioForActivity(activityId, "APPROVED", "L3");
        completeAssignedQuiz(activityId, true);
        approveScenarioFor(activityId, scenarioId);
        return activityId;
    }

    /** 담당 강사가 재사용 후보를 승인해 활동에 붙인 상태를 만든다. 퀴즈만 마쳐서는 붙지 않는다. */
    private void approveScenarioFor(UUID activityId, UUID scenarioId) {
        assertThat(
                        jdbc.update(
                                "UPDATE activity SET scenario_id = ? WHERE activity_id = ? AND scenario_id IS NULL",
                                scenarioId,
                                activityId))
                .isEqualTo(1);
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
                    scenario_version, title, scenario_facts, opening_prompts, origin, created_at)
                SELECT ?, ?, ?, g.content_hash, 1, '합성 승인 시나리오', '[]'::jsonb,
                    '{"S0":"친구는 어떤 기분일까?","S1":"친구가 눈물을 흘리고 있어. 어떤 기분일까?","S2":"친구가 슬퍼 보여. 마음을 함께 말해볼까?"}'::jsonb,
                    'PREPARED', ?
                FROM activity a JOIN learning_goal g ON g.goal_id = a.goal_id
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
