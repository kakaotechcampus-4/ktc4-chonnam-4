package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 최초 결합 퀴즈를 마치면 난이도가 같은 승인 시나리오 중 이 아동이 받은 적 없는 것이 활동에 붙는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class ScenarioAttachmentIntegrationTest {
    // 미리 넣는 시드(R__scenarios.sql)의 L3 예시.
    private static final UUID SEEDED_L3 = UUID.fromString("5ce00000-0000-4000-8000-000000000003");

    @Autowired private MockMvc mvc;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private UserAccountRepository users;
    @Autowired private ChildRepository children;
    @Autowired private QuizItemRepository items;
    @Autowired private ActivityQuizRepository assignments;
    @Autowired private ActivityRepository activities;
    @Autowired private ScenarioRepository scenarios;
    @Autowired private JdbcTemplate jdbc;

    private UUID childId;
    private MockHttpSession childSession;

    @BeforeEach
    void setUp() throws Exception {
        UUID classroomId = UUID.randomUUID();
        childId = UUID.randomUUID();
        TestInstructors.save(users, "teacher-scenario");
        classrooms.save(
                new Classroom(
                        classroomId,
                        TestInstructors.id("teacher-scenario"),
                        "시나리오반",
                        ClassroomStatus.ACTIVE));
        children.save(new Child(childId, classroomId, "시나리오 아동", ChildStatus.ACTIVE));
        String codeBody =
                mvc.perform(
                                post("/api/v1/children/{childId}/access-codes", childId)
                                        .with(TestInstructors.instructor("teacher-scenario"))
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
    void attachesApprovedScenarioOfDecidedLevelWhenFirstQuizEnds() throws Exception {
        UUID activityId = createFirstCombined();
        answerOneItem(activityId, true);

        Activity activity = activities.findById(activityId).orElseThrow();
        assertThat(activity.getScenarioId()).isNotNull();
        assertThat(activity.getScenarioSource()).isNull();
        assertThat(scenarios.findById(activity.getScenarioId()).orElseThrow().getScenarioLevel())
                .isEqualTo("L3");
    }

    @Test
    void skipsDraftRetiredOtherLevelAndScenariosTheChildAlreadyHad() throws Exception {
        // 먼저 넣은 순서로 고르므로, 아래 셋은 모두 시드보다 앞서 있어 걸러지지 않으면 골라진다.
        insertScenario(UUID.randomUUID(), "DRAFT", "L3", "2000-01-01T00:00:00Z");
        insertScenario(UUID.randomUUID(), "RETIRED", "L3", "2000-01-01T00:00:00Z");
        insertScenario(UUID.randomUUID(), "APPROVED", "L2", "2000-01-01T00:00:00Z");
        UUID fresh = UUID.randomUUID();
        insertScenario(fresh, "APPROVED", "L3", "2030-01-01T00:00:00Z");
        // 이 아동은 시드 L3 로 역할극을 이미 했다.
        Activity done = roleplayActivity(SEEDED_L3);
        jdbc.update(
                "update activity set status = 'COMPLETED' where activity_id = ?",
                done.getActivityId());

        UUID activityId = createFirstCombined();
        answerOneItem(activityId, true);

        UUID attached = activities.findById(activityId).orElseThrow().getScenarioId();
        assertThat(attached).isNotEqualTo(SEEDED_L3);
        assertThat(scenarios.findById(attached).orElseThrow().getStatus().name())
                .isEqualTo("APPROVED");
        assertThat(scenarios.findById(attached).orElseThrow().getScenarioLevel()).isEqualTo("L3");
    }

    @Test
    void readingQuizResultRetriesWhenNothingWasAttached() throws Exception {
        UUID activityId = createFirstCombined();
        answerOneItem(activityId, false);
        // 퀴즈를 마칠 때 붙이기가 실패한 상황을 만든다.
        jdbc.update("update activity set scenario_id = null where activity_id = ?", activityId);

        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initialDifficultyUsed").value(true));

        UUID attached = activities.findById(activityId).orElseThrow().getScenarioId();
        assertThat(attached).isNotNull();
        assertThat(scenarios.findById(attached).orElseThrow().getScenarioLevel()).isEqualTo("L1");
    }

    @Test
    void readingResultAgainDoesNotSwapAttachedScenario() throws Exception {
        UUID activityId = createFirstCombined();
        answerOneItem(activityId, true);
        UUID first = activities.findById(activityId).orElseThrow().getScenarioId();

        mvc.perform(get("/api/v1/activities/{id}/quiz-result", activityId).session(childSession))
                .andExpect(status().isOk());

        assertThat(activities.findById(activityId).orElseThrow().getScenarioId()).isEqualTo(first);
    }

    @Test
    void databaseKeepsOneUnfinishedActivityPerChildAndScenario() throws Exception {
        roleplayActivity(SEEDED_L3);

        assertThatThrownBy(() -> roleplayActivity(SEEDED_L3))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createGoal() throws Exception {
        String goalBody =
                mvc.perform(
                                post("/api/v1/children/{childId}/learning-goals", childId)
                                        .with(TestInstructors.instructor("teacher-scenario"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"감정 표현하기\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(JsonPath.read(goalBody, "$.data.goalId"));
    }

    private UUID createFirstCombined() throws Exception {
        UUID goalId = createGoal();
        // 풀이 흐름만 보려고 빈 활동을 저장하고 문항을 저장소로 직접 붙인다(QuizFlowIntegrationTest 와 같다).
        return activities
                .save(
                        new Activity(
                                UUID.randomUUID(),
                                childId,
                                ActivityType.FIRST_COMBINED,
                                goalId,
                                UUID.randomUUID(),
                                Instant.now()))
                .getActivityId();
    }

    /** 문항 하나를 풀어 퀴즈를 끝낸다. 맞히면 L3, 틀리면 L1 이 된다. */
    private void answerOneItem(UUID activityId, boolean correct) throws Exception {
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
        UUID assignmentId = UUID.randomUUID();
        assignments.save(new ActivityQuiz(assignmentId, activityId, itemId, 1, 1));
        String body =
                correct
                        ? "{\"firstResponse\":\"슬픔\"}"
                        : "{\"firstResponse\":\"기쁨\",\"finalResponse\":\"기쁨\"}";
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .session(childSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated());
    }

    private Activity roleplayActivity(UUID scenarioId) throws Exception {
        UUID goalId = createGoal();
        Activity activity =
                activities.saveAndFlush(
                        new Activity(
                                UUID.randomUUID(),
                                childId,
                                ActivityType.ROLEPLAY,
                                goalId,
                                UUID.randomUUID(),
                                Instant.now()));
        jdbc.update(
                "update activity set scenario_id = ? where activity_id = ?",
                scenarioId,
                activity.getActivityId());
        return activity;
    }

    private void insertScenario(UUID id, String status, String level, String createdAt) {
        jdbc.update(
                "insert into scenario (scenario_id, status, scenario_level, title, scenario_facts,"
                        + " origin, created_at) values (?, ?, ?, '테스트 시나리오', '[\"사실\"]',"
                        + " 'PREPARED', ?)",
                id,
                status,
                level,
                Timestamp.from(Instant.parse(createdAt)));
    }
}
