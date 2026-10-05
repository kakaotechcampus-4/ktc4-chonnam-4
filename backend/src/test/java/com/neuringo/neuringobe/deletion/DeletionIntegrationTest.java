package com.neuringo.neuringobe.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아동·학급 영구 삭제(ADR 2026-10-04). 아동이 퀴즈를 끝까지 풀어 응답·힌트·결과가 쌓인 뒤에도 그 아동의 데이터가 한 행도 남지 않고, 같은 학급의 다른 아동과
 * 문항 풀은 그대로인지 본다. 아동 입장 세션이 필요해 {@code QuizFlowIntegrationTest} 와 같은 구성을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class DeletionIntegrationTest {

    private static final String OWNER = "teacher-deletion";
    private static final String OTHER = "other-teacher-deletion";

    @Autowired private MockMvc mvc;
    @Autowired private UserAccountRepository users;
    @Autowired private QuizItemRepository items;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        TestInstructors.save(users, OWNER);
        TestInstructors.save(users, OTHER);
    }

    @Test
    void deletesChildWithAllRecordsAndKeepsSiblingAndQuizPool() throws Exception {
        UUID classId = createClassroom("삭제 테스트반");
        UUID childId = createChild(classId, "삭제할 아동");
        UUID siblingId = createChild(classId, "남을 아동");
        MockHttpSession session = enter(childId);
        UUID activityId = assign(childId);
        solveWithHint(activityId, session);
        assign(siblingId);
        long quizItems = items.count();

        assertThat(rowsOf(childId)).containsValues(1L).doesNotContainValue(0L);

        mvc.perform(delete("/api/v1/children/{id}", childId).with(owner()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(rowsOf(childId)).allSatisfy((table, count) -> assertThat(count).isZero());
        assertThat(rowsOf(siblingId)).containsEntry("child", 1L).containsEntry("activity", 1L);
        assertThat(items.count()).isEqualTo(quizItems);
        mvc.perform(get("/api/v1/children/{id}", childId).with(owner()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHILD_NOT_FOUND"));
        // 입장해 있던 아동의 다음 요청은 바로 막힌다.
        mvc.perform(get("/api/v1/children/{id}/activities", childId).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesClassroomWithItsChildrenAndLeavesOtherClassrooms() throws Exception {
        UUID classId = createClassroom("지울 반");
        UUID first = createChild(classId, "첫째 아동");
        UUID second = createChild(classId, "둘째 아동");
        solveWithHint(assign(first), enter(first));
        assign(second);
        UUID otherClass = createClassroom("남을 반");
        UUID otherChild = createChild(otherClass, "다른 반 아동");
        assign(otherChild);

        mvc.perform(delete("/api/v1/classrooms/{id}", classId).with(owner()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(rowsOf(first)).allSatisfy((table, count) -> assertThat(count).isZero());
        assertThat(rowsOf(second)).allSatisfy((table, count) -> assertThat(count).isZero());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from classroom where class_id = ?",
                                Long.class,
                                classId))
                .isZero();
        assertThat(rowsOf(otherChild)).containsEntry("child", 1L).containsEntry("activity", 1L);
        mvc.perform(get("/api/v1/classrooms/{id}", classId).with(owner()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesEmptyClassroom() throws Exception {
        UUID classId = createClassroom("빈 반");

        mvc.perform(delete("/api/v1/classrooms/{id}", classId).with(owner()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/classrooms/{id}", classId).with(owner()))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherInstructorSeesNotFoundAndNothingIsDeleted() throws Exception {
        UUID classId = createClassroom("남의 반");
        UUID childId = createChild(classId, "남의 아동");
        assign(childId);

        mvc.perform(
                        delete("/api/v1/children/{id}", childId)
                                .with(TestInstructors.instructor(OTHER))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHILD_NOT_FOUND"));
        mvc.perform(
                        delete("/api/v1/classrooms/{id}", classId)
                                .with(TestInstructors.instructor(OTHER))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLASSROOM_NOT_FOUND"));

        assertThat(rowsOf(childId)).containsEntry("child", 1L).containsEntry("activity", 1L);
    }

    @Test
    void unknownIdsAreNotFound() throws Exception {
        mvc.perform(delete("/api/v1/children/{id}", UUID.randomUUID()).with(owner()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHILD_NOT_FOUND"));
        mvc.perform(delete("/api/v1/classrooms/{id}", UUID.randomUUID()).with(owner()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLASSROOM_NOT_FOUND"));
    }

    /**
     * 학급 삭제가 학급을 잠그고 아동 목록을 읽은 순간에 같은 학급으로 아동 등록이 들어오는 경우(Codex 검토 2026-10-04 P2). 등록은 삭제가 끝날 때까지
     * 기다려야 하고, 끝난 뒤에는 500 이 아니라 학급 없음 404 가 되어야 한다. 삭제 트랜잭션을 테스트가 직접 열어 "목록을 읽은 직후"에 멈춰 둔다.
     */
    @Test
    void childRegistrationWaitsForClassroomDeletionAndThenIsNotFound() throws Exception {
        UUID classId = createClassroom("삭제 중인 반");
        UUID instructorId = TestInstructors.id(OWNER);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> registration =
                    transactions.execute(
                            tx -> {
                                classrooms.findOwnedForUpdate(classId, instructorId).orElseThrow();
                                Future<MvcResult> pending =
                                        pool.submit(() -> registerChild(classId, "끼어든 아동"));
                                assertThatThrownBy(() -> pending.get(1, TimeUnit.SECONDS))
                                        .isInstanceOf(TimeoutException.class);
                                classrooms.deleteByClassId(classId);
                                return pending;
                            });

            MvcResult result = registration.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(
                            JsonPath.<String>read(
                                    result.getResponse().getContentAsString(), "$.error.code"))
                    .isEqualTo("CLASSROOM_NOT_FOUND");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from child where class_id = ?",
                                    Long.class,
                                    classId))
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 아동 한 명에 걸린 행 수. 삭제 뒤에는 모든 표가 0 이어야 한다. */
    private Map<String, Long> rowsOf(UUID childId) {
        String activities = "select activity_id from activity where child_id = ?";
        String assigned =
                "select activity_quiz_id from activity_quiz where activity_id in ("
                        + activities
                        + ")";
        String attempts =
                "select attempt_id from quiz_attempt where activity_quiz_id in (" + assigned + ")";
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("child", "select count(*) from child where child_id = ?");
        queries.put(
                "child_access_code", "select count(*) from child_access_code where child_id = ?");
        queries.put("learning_goal", "select count(*) from learning_goal where child_id = ?");
        queries.put("activity", "select count(*) from activity where child_id = ?");
        queries.put(
                "activity_quiz",
                "select count(*) from activity_quiz where activity_id in (" + activities + ")");
        queries.put(
                "quiz_attempt",
                "select count(*) from quiz_attempt where activity_quiz_id in (" + assigned + ")");
        queries.put(
                "quiz_hint",
                "select count(*) from quiz_hint where attempt_id in (" + attempts + ")");
        queries.put(
                "quiz_result",
                "select count(*) from quiz_result where activity_id in (" + activities + ")");
        Map<String, Long> rows = new LinkedHashMap<>();
        queries.forEach(
                (table, sql) -> rows.put(table, jdbc.queryForObject(sql, Long.class, childId)));
        return rows;
    }

    /** 세 문항을 모두 푼다. 두 번째 문항은 일부러 틀리고 힌트를 받아 힌트 행도 남긴다. 다 풀면 결과가 저장된다. */
    private void solveWithHint(UUID activityId, MockHttpSession session) throws Exception {
        String body =
                mvc.perform(get("/api/v1/activities/{id}/quiz-items", activityId).session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        List<Map<String, Object>> assigned = JsonPath.read(body, "$.data");
        for (Map<String, Object> question : assigned) {
            UUID assignmentId = UUID.fromString((String) question.get("activityQuizId"));
            QuizItem item =
                    items.findById(UUID.fromString((String) question.get("itemId"))).orElseThrow();
            String wrong =
                    item.getChoices().stream()
                            .filter(choice -> !choice.equals(item.getCorrectAnswer()))
                            .findFirst()
                            .orElseThrow();
            if ("SELF_EMOTION_SITUATION".equals(question.get("quizType"))) {
                answer(
                        assignmentId,
                        session,
                        "{\"firstResponse\":\""
                                + item.getCorrectAnswer()
                                + "\",\"expressionMatchResult\":\"NOT_ANALYZABLE\","
                                + "\"cameraModelVersion\":\"camera-v1\"}");
                continue;
            }
            answer(assignmentId, session, "{\"firstResponse\":\"" + wrong + "\"}");
            mvc.perform(
                            post("/api/v1/activity-quiz-items/{id}/hints", assignmentId)
                                    .session(session)
                                    .with(csrf())
                                    .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isCreated());
            answer(
                    assignmentId,
                    session,
                    "{\"firstResponse\":\""
                            + wrong
                            + "\",\"finalResponse\":\""
                            + item.getCorrectAnswer()
                            + "\"}");
        }
    }

    private void answer(UUID assignmentId, MockHttpSession session, String json) throws Exception {
        mvc.perform(
                        put("/api/v1/activity-quiz-items/{id}/attempt", assignmentId)
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andExpect(status().is2xxSuccessful());
    }

    /** 입장 코드를 발급받아 아동으로 입장한다. 입장 코드 행이 생긴다. */
    private MockHttpSession enter(UUID childId) throws Exception {
        String codeBody =
                mvc.perform(
                                post("/api/v1/children/{id}/access-codes", childId)
                                        .with(owner())
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
        return (MockHttpSession) entry.getRequest().getSession(false);
    }

    /** 목표를 저장하고 활동을 배정한다. 서버가 문항 3개를 붙인다. */
    private UUID assign(UUID childId) throws Exception {
        String goal =
                postJson(
                        "/api/v1/children/" + childId + "/learning-goals",
                        "{\"title\":\"친구 감정 알아보기 " + UUID.randomUUID() + "\"}");
        String activity =
                mvc.perform(
                                post("/api/v1/activities")
                                        .with(owner())
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"childId\":\""
                                                        + childId
                                                        + "\",\"goalId\":\""
                                                        + JsonPath.read(goal, "$.data.goalId")
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(JsonPath.read(activity, "$.data.activityId"));
    }

    private UUID createClassroom(String name) throws Exception {
        return UUID.fromString(
                JsonPath.read(
                        postJson("/api/v1/classrooms", "{\"name\":\"" + name + "\"}"),
                        "$.data.classId"));
    }

    private UUID createChild(UUID classId, String name) throws Exception {
        return UUID.fromString(
                JsonPath.read(
                        postJson(
                                "/api/v1/classrooms/" + classId + "/children",
                                "{\"displayName\":\"" + name + "\"}"),
                        "$.data.childId"));
    }

    /** 아동 등록 요청을 보내고 상태 코드와 상관없이 결과를 돌려준다. */
    private MvcResult registerChild(UUID classId, String name) throws Exception {
        return mvc.perform(
                        post("/api/v1/classrooms/{id}/children", classId)
                                .with(owner())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"" + name + "\"}"))
                .andReturn();
    }

    private String postJson(String uri, String json) throws Exception {
        return mvc.perform(
                        post(uri)
                                .with(owner())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static RequestPostProcessor owner() {
        return TestInstructors.instructor(OWNER);
    }
}
