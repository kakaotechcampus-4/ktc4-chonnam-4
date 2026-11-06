package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.auth.domain.AuthSession;
import com.neuringo.neuringobe.auth.dto.LoginRequest;
import com.neuringo.neuringobe.auth.dto.LoginResponse;
import com.neuringo.neuringobe.auth.repository.AuthSessionRepository;
import com.neuringo.neuringobe.auth.service.AuthService;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildAccessCode;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildAccessCodeRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizAttempt;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizAttemptRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.domain.AccountStatus;
import com.neuringo.neuringobe.user.domain.UserAccount;
import com.neuringo.neuringobe.user.domain.UserRole;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 저장 시각은 PostgreSQL {@code TIMESTAMPTZ} 의 해상도(1마이크로초)로 맞춘 뒤 엔티티에 들어간다(#104).
 *
 * <p>나노초 시각을 그대로 두면 저장 직후 응답은 메모리 값({@code .512881776})을, 재전송·조회 응답은 DB 가 반올림한 값({@code .512882})을
 * 보여 줘 같은 자원의 응답이 달라진다. 앞 절은 실제 현재 시각에 기대지 않도록 고정 나노초 입력을 저장·재조회해 메모리 값과 DB 값이 정확히 같은지 본다. 뒤 절은
 * HTTP 로 같은 요청을 다시 보내거나 다시 읽었을 때 시각을 포함한 응답 전체가 같은지 본다. 시각을 비교에서 빼거나 허용 오차를 두지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class StoredTimePrecisionIntegrationTest {
    private static final String TEACHER = "teacher-stored-time";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthService auth;
    @Autowired private UserAccountRepository users;
    @Autowired private AuthSessionRepository authSessions;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private ChildRepository children;
    @Autowired private ChildAccessCodeRepository accessCodes;
    @Autowired private LearningGoalRepository goals;
    @Autowired private ActivityRepository activities;
    @Autowired private QuizItemRepository items;
    @Autowired private ActivityQuizRepository assignments;
    @Autowired private QuizAttemptRepository attempts;

    private UUID childId;

    @BeforeEach
    void setUp() {
        TestInstructors.save(users, TEACHER);
        UUID classroomId = UUID.randomUUID();
        classrooms.save(
                new Classroom(
                        classroomId, TestInstructors.id(TEACHER), "시각반", ClassroomStatus.ACTIVE));
        childId = UUID.randomUUID();
        children.save(new Child(childId, classroomId, "시각 아동", ChildStatus.ACTIVE));
    }

    // ── 저장 계층: 고정 입력 ──────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // 나노초 자리를 버린다. DB 에 그대로 보냈다면 .123457 로 반올림됐다.
        "2026-11-05T05:00:00.123456789Z, 2026-11-05T05:00:00.123456Z",
        // 다음 초로 넘어가지 않는다. DB 에 그대로 보냈다면 05:00:01 이 됐다.
        "2026-11-05T05:00:00.999999999Z, 2026-11-05T05:00:00.999999Z",
        // 이미 마이크로초 단위인 값은 바뀌지 않는다.
        "2026-11-05T05:00:00.123456Z, 2026-11-05T05:00:00.123456Z"
    })
    void storedTimesReloadExactlyAsHeldInMemory(String input, String stored) {
        Instant at = Instant.parse(input);
        Instant expected = Instant.parse(stored);

        Activity activity =
                new Activity(
                        UUID.randomUUID(),
                        childId,
                        ActivityType.OTHER_EMOTION_QUIZ,
                        null,
                        UUID.randomUUID(),
                        at);
        activity.complete(at);
        activities.saveAndFlush(activity);
        Activity reloadedActivity = activities.findById(activity.getActivityId()).orElseThrow();
        assertThat(activity.getAssignedAt()).isEqualTo(expected);
        assertThat(
                        List.of(
                                reloadedActivity.getAssignedAt(),
                                reloadedActivity.getStartedAt(),
                                reloadedActivity.getCompletedAt(),
                                reloadedActivity.getRewardIssuedAt()))
                .containsExactly(
                        activity.getAssignedAt(),
                        activity.getStartedAt(),
                        activity.getCompletedAt(),
                        activity.getRewardIssuedAt());

        ChildAccessCode code =
                new ChildAccessCode(
                        UUID.randomUUID(),
                        childId,
                        TestInstructors.id(TEACHER),
                        UUID.randomUUID(),
                        randomHex64(),
                        0,
                        at,
                        at.plus(Duration.ofMinutes(10)));
        accessCodes.saveAndFlush(code);
        assertThat(code.getExpiresAt()).isEqualTo(expected.plus(Duration.ofMinutes(10)));
        assertThat(accessCodes.findById(code.getCodeId()).orElseThrow().getExpiresAt())
                .isEqualTo(code.getExpiresAt());
        assertThat(storedInstant("child_access_code", "issued_at", "code_id", code.getCodeId()))
                .isEqualTo(expected);

        LearningGoal goal =
                new LearningGoal(
                        UUID.randomUUID(),
                        childId,
                        TestInstructors.id(TEACHER),
                        null,
                        "시각 목표",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        randomHex64(),
                        at);
        goals.saveAndFlush(goal);
        assertThat(goal.getCreatedAt()).isEqualTo(expected);
        assertThat(goals.findById(goal.getGoalId()).orElseThrow().getCreatedAt())
                .isEqualTo(goal.getCreatedAt());

        AuthSession session =
                new AuthSession(
                        UUID.randomUUID(),
                        TestInstructors.id(TEACHER),
                        randomHex64(),
                        at.plus(Duration.ofHours(1)),
                        at);
        authSessions.saveAndFlush(session);
        AuthSession reloadedSession = authSessions.findById(session.getSessionId()).orElseThrow();
        assertThat(session.getCreatedAt()).isEqualTo(expected);
        assertThat(reloadedSession.getCreatedAt()).isEqualTo(session.getCreatedAt());
        assertThat(reloadedSession.getExpiresAt()).isEqualTo(session.getExpiresAt());

        UUID assignmentId = assignQuiz(independentQuiz(), 1);
        QuizAttempt attempt =
                new QuizAttempt(
                        UUID.randomUUID(), assignmentId, "슬픔", true, null, null, null, false, at);
        attempts.saveAndFlush(attempt);
        attempt.finalizeWith("슬픔", true, false, at);
        attempts.saveAndFlush(attempt);
        assertThat(attempt.getRespondedAt()).isEqualTo(expected);
        assertThat(attempts.findById(attempt.getAttemptId()).orElseThrow().getRespondedAt())
                .isEqualTo(attempt.getRespondedAt());
        assertThat(
                        storedInstant(
                                "quiz_attempt",
                                "finalized_at",
                                "attempt_id",
                                attempt.getAttemptId()))
                .isEqualTo(expected);
    }

    /**
     * 클라이언트에 알린 만료 시각(메모리)과 서버가 DB 에서 읽어 판단하는 만료 시각이 같아야 직전·정각·이후 판정이 어긋나지 않는다. 정규화 전에는 원래 나노초
     * 시각({@code .123456789})에서 메모리는 만료, DB({@code .123457})는 유효로 갈렸다.
     */
    @Test
    void expiryJudgementIsTheSameBeforeAndAfterReload() {
        Instant requested = Instant.parse("2026-11-05T05:00:00.123456789Z");
        Instant stored = Instant.parse("2026-11-05T05:00:00.123456Z");
        AuthSession session =
                new AuthSession(
                        UUID.randomUUID(),
                        TestInstructors.id(TEACHER),
                        randomHex64(),
                        requested,
                        requested.minus(Duration.ofHours(1)));
        authSessions.saveAndFlush(session);
        AuthSession reloaded = authSessions.findById(session.getSessionId()).orElseThrow();

        Instant justBefore = stored.minus(1, ChronoUnit.MICROS);
        Instant justAfter = stored.plus(1, ChronoUnit.MICROS);
        assertThat(session.isExpiredAt(justBefore)).isFalse();
        assertThat(session.isExpiredAt(stored)).isTrue();
        assertThat(session.isExpiredAt(justAfter)).isTrue();
        for (Instant probe : List.of(justBefore, stored, requested, justAfter)) {
            assertThat(reloaded.isExpiredAt(probe))
                    .as("probe %s", probe)
                    .isEqualTo(session.isExpiredAt(probe));
        }
    }

    // ── HTTP: 같은 요청 재전송·재조회 ─────────────────────────────────────

    @Test
    void sameAssignmentKeyReplaysTheSameAssignedAt() throws Exception {
        UUID key = UUID.randomUUID();
        String body = "{\"childId\":\"" + childId + "\",\"goal\":{\"title\":\"친구 감정 알아보기\"}}";

        Map<String, Object> first =
                data(
                        mvc.perform(
                                        post("/api/v1/activities")
                                                .with(TestInstructors.instructor(TEACHER))
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andExpect(status().isCreated()));
        Map<String, Object> replay =
                data(
                        mvc.perform(
                                        post("/api/v1/activities")
                                                .with(TestInstructors.instructor(TEACHER))
                                                .with(csrf())
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andExpect(status().isOk()));

        assertThat(replay).isEqualTo(first);
        // 상세·목록은 생성 응답과 다른 조회용 필드가 더 붙을 수 있어 같은 활동의 assignedAt 만 견준다.
        String detail =
                body(
                        mvc.perform(
                                        get("/api/v1/activities/{id}", first.get("activityId"))
                                                .with(TestInstructors.instructor(TEACHER)))
                                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(detail, "$.data.activity.activityId"))
                .isEqualTo(first.get("activityId"));
        assertThat(JsonPath.<String>read(detail, "$.data.activity.assignedAt"))
                .isEqualTo(first.get("assignedAt"));
        String list =
                body(
                        mvc.perform(
                                        get("/api/v1/children/{id}/activities", childId)
                                                .with(TestInstructors.instructor(TEACHER)))
                                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(list, "$.data[*].activityId"))
                .containsExactly((String) first.get("activityId"));
        assertThat(JsonPath.<List<String>>read(list, "$.data[*].assignedAt"))
                .containsExactly((String) first.get("assignedAt"));
    }

    @Test
    void sameAccessCodeKeyReplaysTheSameExpiry() throws Exception {
        UUID key = UUID.randomUUID();

        Map<String, Object> first = data(issueAccessCode(key));
        Map<String, Object> replay = data(issueAccessCode(key));

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void createdGoalReadsBackWithTheSameCreatedAt() throws Exception {
        Map<String, Object> created =
                data(
                        mvc.perform(
                                        post("/api/v1/children/{childId}/learning-goals", childId)
                                                .with(TestInstructors.instructor(TEACHER))
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"title\":\"감정 표현하기\"}"))
                                .andExpect(status().isCreated()));
        Map<String, Object> read =
                data(
                        mvc.perform(
                                        get("/api/v1/learning-goals/{id}", created.get("goalId"))
                                                .with(TestInstructors.instructor(TEACHER)))
                                .andExpect(status().isOk()));

        assertThat(read).isEqualTo(created);
    }

    @Test
    void sameQuizAnswerReplaysTheSameRespondedAtWithoutAddingAnAttempt() throws Exception {
        MockHttpSession childSession = enterAsChild();
        UUID activityId = independentQuiz();
        UUID assignmentId = assignQuiz(activityId, 1);
        assignQuiz(activityId, 2);
        String answer = "{\"firstResponse\":\"슬픔\"}";

        Map<String, Object> first =
                data(
                        mvc.perform(answer(assignmentId, answer, childSession))
                                .andExpect(status().isCreated()));
        Map<String, Object> replay =
                data(
                        mvc.perform(answer(assignmentId, answer, childSession))
                                .andExpect(status().isOk()));

        assertThat(replay).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from quiz_attempt where activity_quiz_id = ?",
                                Integer.class,
                                assignmentId))
                .isEqualTo(1);
    }

    @Test
    void loginExpiryMatchesTheStoredSessionUsedForAuthentication() {
        String email = "stored-time-" + UUID.randomUUID() + "@example.test";
        String password = "stored-time-password";
        UUID userId = UUID.randomUUID();
        users.save(
                new UserAccount(
                        userId,
                        email,
                        passwordEncoder.encode(password),
                        "시각 강사",
                        null,
                        UserRole.INSTRUCTOR,
                        AccountStatus.ACTIVE,
                        Instant.now(),
                        "v1",
                        "v1",
                        Instant.now()));

        LoginResponse login = auth.login(new LoginRequest(email, password));

        Timestamp stored =
                jdbc.queryForObject(
                        "select expires_at from auth_session where user_id = ?",
                        Timestamp.class,
                        userId);
        assertThat(stored.toInstant()).isEqualTo(login.expiresAt());
        assertThat(auth.authenticate(login.accessToken())).isPresent();
    }

    private ResultActions issueAccessCode(UUID key) throws Exception {
        return mvc.perform(
                        post("/api/v1/children/{childId}/access-codes", childId)
                                .with(TestInstructors.instructor(TEACHER))
                                .with(csrf())
                                .header("Idempotency-Key", key))
                .andExpect(status().isCreated());
    }

    private MockHttpSession enterAsChild() throws Exception {
        String code = JsonPath.read(body(issueAccessCode(UUID.randomUUID())), "$.data.accessCode");
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

    private org.springframework.test.web.servlet.RequestBuilder answer(
            UUID assignmentId, String body, MockHttpSession childSession) {
        return put("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                .session(childSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private UUID independentQuiz() {
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

    /** 정답이 "슬픔"인 상대 감정 문항을 활동에 직접 붙인다(배정 API 는 문항을 무작위로 고른다). */
    private UUID assignQuiz(UUID activityId, int order) {
        UUID itemId = UUID.randomUUID();
        items.save(
                new QuizItem(
                        itemId,
                        QuizType.OTHER_EMOTION_SITUATION,
                        Emotion.SADNESS,
                        "친구는 어떤 감정일까요?",
                        null,
                        List.of("기쁨", "슬픔"),
                        "슬픔",
                        List.of("슬픔"),
                        List.of(
                                new QuizItem.HintSpec("OBSERVABLE_FACE_CUE", "얼굴을 살펴봐요."),
                                new QuizItem.HintSpec("CHOICE_REDUCTION", "둘 중 골라봐요.")),
                        1));
        UUID assignmentId = UUID.randomUUID();
        assignments.save(new ActivityQuiz(assignmentId, activityId, itemId, 1, order));
        return assignmentId;
    }

    private Instant storedInstant(String table, String column, String idColumn, UUID id) {
        return jdbc.queryForObject(
                        "select " + column + " from " + table + " where " + idColumn + " = ?",
                        Timestamp.class,
                        id)
                .toInstant();
    }

    /** 토큰 해시·코드 다이제스트 칸(64자)에 넣을 겹치지 않는 값. */
    private static String randomHex64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Object> data(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.data");
    }
}
